package com.web.service.ticket;

import com.web.dto.ticket.reservations.SeatHoldCreateRequest;
import com.web.dto.ticket.reservations.SeatHoldRequest;
import com.web.dto.ticket.reservations.SeatHoldResponse;
import com.web.dto.ticket.reservations.mapper.SeatHoldMapper;
import com.web.entity.SeatHold;
import com.web.entity.Trip;
import com.web.entity.User;
import com.web.exception.ResourceNotFoundException;
import com.web.exception.SeatNotAvailableException;
import com.web.repository.SeatHoldRepository;
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
    private final UserRepository userRepository;
    private final SeatHoldMapper seatHoldMapper;
    private final ConfigService configService;


    // Crea un hold de asiento (método sobrecargado para controller)
    @Override
    @Transactional
    public SeatHoldResponse createHold(Long tripId, Integer seatNumber, SeatHoldRequest request) {

        SeatHoldCreateRequest internalRequest = new SeatHoldCreateRequest(
                tripId,
                seatNumber,
                request.userId()
        );
        return createHold(internalRequest, request.userId());
    }

    // Crea un hold temporal de asiento validando disponibilidad y expiración
    @Override
    @Transactional
    public SeatHoldResponse createHold(SeatHoldCreateRequest request, Long userId) {
        LocalDateTime now = LocalDateTime.now();

        Trip trip = tripRepository.findById(request.tripId())
                .orElseThrow(() -> new ResourceNotFoundException("Viaje", request.tripId()));

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario", userId));

        if (trip.getStatus() != Trip.TripStatus.SCHEDULED) {
            throw new SeatNotAvailableException(
                    "El viaje no admite reservas (estado: " + trip.getStatus() + ")");
        }

        if (trip.getDepartureTime().isBefore(now)) {
            throw new SeatNotAvailableException("El viaje ya ha salido");
        }

        if (request.seatNumber() < 1 || request.seatNumber() > trip.getBus().getCapacity()) {
            throw new SeatNotAvailableException(
                    "El asiento " + request.seatNumber() + " no existe en este bus (capacidad: " +
                            trip.getBus().getCapacity() + ")"
            );
        }

        Optional<SeatHold> activeHold = seatHoldRepository.findActiveHold(
                request.tripId(),
                request.seatNumber(),
                now
        );

        if (activeHold.isPresent()) {
            SeatHold existingHold = activeHold.get();
            if (existingHold.getUser().getId().equals(userId)) {
                return seatHoldMapper.toResponse(existingHold);
            }
            throw new SeatNotAvailableException(
                    "El asiento " + request.seatNumber() + " ya tiene un hold activo hasta " +
                            existingHold.getExpiresAt()
            );
        }

        Boolean isSeatAvailable = ticketRepository.isSeatAvailableForFullTrip(
                request.tripId(),
                request.seatNumber()
        );

        if (!isSeatAvailable) {
            throw new SeatNotAvailableException(
                    "El asiento " + request.seatNumber() + " ya está vendido"
            );
        }

        Integer holdDurationMinutes = configService.getHoldDurationMinutes();
        LocalDateTime expiresAt = now.plusMinutes(holdDurationMinutes);

        SeatHold seatHold = seatHoldMapper.toEntity(request);
        // Establecer las relaciones manualmente
        seatHold.setTrip(trip);
        seatHold.setUser(user);
        seatHold.setExpiresAt(expiresAt);
        seatHold = seatHoldRepository.save(seatHold);


        return seatHoldMapper.toResponse(seatHold);
    }

    // Verifica si un asiento tiene un hold activo
    @Override
    @Transactional(readOnly = true)
    public boolean hasActiveHold(Long tripId, Integer seatNumber) {
        LocalDateTime now = LocalDateTime.now();
        Optional<SeatHold> activeHold = seatHoldRepository.findActiveHold(tripId, seatNumber, now);
        return activeHold.isPresent();
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


