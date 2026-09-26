package com.web.service.ticket;

import com.web.dto.ticket.reservations.SeatHoldCreateRequest;
import com.web.dto.ticket.reservations.SeatHoldRequest;
import com.web.dto.ticket.reservations.SeatHoldResponse;
import com.web.dto.ticket.reservations.mapper.SeatHoldMapper;
import com.web.entity.SeatHold;
import com.web.entity.Stop;
import com.web.entity.Trip;
import com.web.entity.User;
import com.web.exception.InvalidSegmentException;
import com.web.exception.ResourceNotFoundException;
import com.web.exception.SeatNotAvailableException;
import com.web.repository.SeatHoldRepository;
import com.web.repository.StopRepository;
import com.web.repository.TicketRepository;
import com.web.repository.TripRepository;
import com.web.repository.UserRepository;
import com.web.service.admin.ConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;


@Service
@RequiredArgsConstructor
public class SeatHoldServiceImpl implements SeatHoldService {

    private final SeatHoldRepository seatHoldRepository;
    private final TicketRepository ticketRepository;
    private final TripRepository tripRepository;
    private final StopRepository stopRepository;
    private final UserRepository userRepository;
    private final SeatHoldMapper seatHoldMapper;
    private final ConfigService configService;


    // Crea un hold de asiento para el tramo indicado (usado por el controller)
    @Override
    @Transactional
    public SeatHoldResponse createHold(Long tripId, Integer seatNumber, SeatHoldRequest request) {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new ResourceNotFoundException("Viaje", tripId));

        Stop fromStop = stopRepository.findById(request.fromStopId())
                .orElseThrow(() -> new ResourceNotFoundException("Parada origen", request.fromStopId()));
        Stop toStop = stopRepository.findById(request.toStopId())
                .orElseThrow(() -> new ResourceNotFoundException("Parada destino", request.toStopId()));

        Long routeId = trip.getRoute().getId();
        if (!fromStop.getRoute().getId().equals(routeId) || !toStop.getRoute().getId().equals(routeId)) {
            throw new InvalidSegmentException("Las paradas no pertenecen a la ruta del viaje");
        }
        if (fromStop.getOrder() >= toStop.getOrder()) {
            throw new InvalidSegmentException("La parada de origen debe ser anterior a la de destino");
        }

        return createHold(trip, seatNumber, request.userId(), fromStop, toStop);
    }

    // Crea un hold sobre todo el viaje (sin tramo)
    @Override
    @Transactional
    public SeatHoldResponse createHold(SeatHoldCreateRequest request, Long userId) {
        Trip trip = tripRepository.findById(request.tripId())
                .orElseThrow(() -> new ResourceNotFoundException("Viaje", request.tripId()));

        return createHold(trip, request.seatNumber(), userId, null, null);
    }

    // Crea un hold temporal validando estado del viaje, número de asiento, holds y ventas que se solapan.
    // fromStop/toStop null significa que el hold bloquea el asiento en todo el viaje.
    private SeatHoldResponse createHold(Trip trip, Integer seatNumber, Long userId, Stop fromStop, Stop toStop) {
        LocalDateTime now = LocalDateTime.now();
        Long tripId = trip.getId();

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario", userId));

        if (trip.getStatus() != Trip.TripStatus.SCHEDULED) {
            throw new SeatNotAvailableException(
                    "El viaje no admite reservas (estado: " + trip.getStatus() + ")");
        }

        if (trip.getDepartureTime().isBefore(now)) {
            throw new SeatNotAvailableException("El viaje ya ha salido");
        }

        if (seatNumber < 1 || seatNumber > trip.getBus().getCapacity()) {
            throw new SeatNotAvailableException(
                    "El asiento " + seatNumber + " no existe en este bus (capacidad: " +
                            trip.getBus().getCapacity() + ")"
            );
        }

        boolean fullTrip = fromStop == null || toStop == null;
        int fromOrder = fullTrip ? Integer.MIN_VALUE : fromStop.getOrder();
        int toOrder = fullTrip ? Integer.MAX_VALUE : toStop.getOrder();

        List<SeatHold> overlappingHolds = seatHoldRepository.findOverlappingActiveHolds(
                tripId, seatNumber, fromOrder, toOrder, now);

        for (SeatHold existingHold : overlappingHolds) {
            if (!existingHold.getUser().getId().equals(userId)) {
                throw new SeatNotAvailableException(
                        "El asiento " + seatNumber + " ya tiene un hold activo hasta " +
                                existingHold.getExpiresAt()
                );
            }
        }

        // Si el usuario ya tenía un hold sobre el mismo tramo se devuelve ese mismo hold;
        // si tenía uno sobre otro tramo que se solapa, se reemplaza por el nuevo
        Optional<SeatHold> sameSegmentHold = overlappingHolds.stream()
                .filter(h -> isSameSegment(h, fromStop, toStop))
                .findFirst();
        if (sameSegmentHold.isPresent()) {
            return seatHoldMapper.toResponse(sameSegmentHold.get());
        }

        boolean isSeatAvailable = fullTrip
                ? Boolean.TRUE.equals(ticketRepository.isSeatAvailableForFullTrip(tripId, seatNumber))
                : ticketRepository.isSeatAvailableForSegment(tripId, seatNumber, fromOrder, toOrder);

        if (!isSeatAvailable) {
            throw new SeatNotAvailableException(
                    "El asiento " + seatNumber + " ya está vendido para el tramo seleccionado"
            );
        }

        for (SeatHold replacedHold : overlappingHolds) {
            replacedHold.setStatus(SeatHold.HoldStatus.EXPIRED);
            seatHoldRepository.save(replacedHold);
        }

        Integer holdDurationMinutes = configService.getHoldDurationMinutes();

        SeatHold seatHold = SeatHold.builder()
                .trip(trip)
                .seatNumber(seatNumber)
                .user(user)
                .fromStop(fromStop)
                .toStop(toStop)
                .expiresAt(now.plusMinutes(holdDurationMinutes))
                .status(SeatHold.HoldStatus.HOLD)
                .build();
        seatHold = seatHoldRepository.save(seatHold);

        return seatHoldMapper.toResponse(seatHold);
    }

    private boolean isSameSegment(SeatHold hold, Stop fromStop, Stop toStop) {
        if (fromStop == null || toStop == null) {
            return hold.getFromStop() == null || hold.getToStop() == null;
        }
        return hold.getFromStop() != null && hold.getToStop() != null
                && hold.getFromStop().getId().equals(fromStop.getId())
                && hold.getToStop().getId().equals(toStop.getId());
    }

    // Verifica si un asiento tiene un hold activo
    @Override
    @Transactional(readOnly = true)
    public boolean hasActiveHold(Long tripId, Integer seatNumber) {
        LocalDateTime now = LocalDateTime.now();
        return !seatHoldRepository.findActiveHolds(tripId, seatNumber, now).isEmpty();
    }

    // Libera un hold cambiando su estado a SOLD (usado cuando se compra el ticket)
    @Override
    @Transactional
    public void releaseHold(Long holdId) {
        SeatHold hold = seatHoldRepository.findById(holdId)
                .orElseThrow(() -> new ResourceNotFoundException("Hold", holdId));

        hold.setStatus(SeatHold.HoldStatus.SOLD);
        seatHoldRepository.save(hold);


    }

    // Busca un hold activo específico de un usuario para un asiento en un viaje
    @Override
    @Transactional(readOnly = true)
    public Optional<SeatHoldResponse> findUserActiveHold(Long tripId, Integer seatNumber, Long userId) {
        LocalDateTime now = LocalDateTime.now();
        List<SeatHold> holds = seatHoldRepository.findUserActiveHoldsForTrip(tripId, userId, now);

        Optional<SeatHold> hold = holds.stream()
                .filter(h -> h.getSeatNumber().equals(seatNumber))
                .findFirst();

        return hold.map(seatHoldMapper::toResponse);
    }

    // Obtiene todos los holds activos de un viaje específico
    @Override
    @Transactional(readOnly = true)
    public List<SeatHoldResponse> getActiveHoldsByTrip(Long tripId) {
        LocalDateTime now = LocalDateTime.now();
        List<SeatHold> holds = seatHoldRepository.findActiveHoldsByTrip(tripId, now);
        return seatHoldMapper.toResponseList(holds);
    }

    // Obtiene todos los holds activos de un usuario (filtrados por fecha de expiración)
    @Override
    @Transactional(readOnly = true)
    public List<SeatHoldResponse> getUserActiveHolds(Long userId) {
        List<SeatHold> holds = seatHoldRepository.findByUserIdAndStatus(userId, SeatHold.HoldStatus.HOLD);
        LocalDateTime now = LocalDateTime.now();
        List<SeatHold> activeHolds = holds.stream()
                .filter(h -> h.getExpiresAt().isAfter(now))
                .toList();
        return seatHoldMapper.toResponseList(activeHolds);
    }

    // Expira holds antiguos automáticamente cada 60 segundos (tarea programada)
    @Scheduled(fixedRate = 60000) //60 segundos
    @Transactional
    public void expireOldHolds() {
        LocalDateTime now = LocalDateTime.now();

        int expiredCount = seatHoldRepository.expireHolds(now);
    }
}


