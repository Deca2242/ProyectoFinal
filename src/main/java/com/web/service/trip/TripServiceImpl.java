package com.web.service.trip;

import com.web.dto.ticket.TicketResponse;
import com.web.dto.ticket.mapper.TicketMapper;
import com.web.dto.trip.TripCreateRequest;
import com.web.dto.trip.TripDetailResponse;
import com.web.dto.trip.TripResponse;
import com.web.dto.trip.SeatAvailabilityResponse;
import com.web.dto.trip.SeatStatusResponse;
import com.web.dto.trip.mapper.TripMapper;
import com.web.entity.Bus;
import com.web.entity.Route;
import com.web.entity.SeatHold;
import com.web.entity.Stop;
import com.web.entity.Ticket;
import com.web.entity.Trip;
import com.web.exception.BusinessException;
import com.web.exception.ResourceNotFoundException;
import com.web.repository.BusRepository;
import com.web.repository.RouteRepository;
import com.web.repository.SeatHoldRepository;
import com.web.repository.StopRepository;
import com.web.repository.TicketRepository;
import com.web.repository.TripRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TripServiceImpl implements TripService {

        private final TripRepository tripRepository;
        private final RouteRepository routeRepository;
        private final BusRepository busRepository;
        private final StopRepository stopRepository;
        private final TicketRepository ticketRepository;
        private final SeatHoldRepository seatHoldRepository;
        private final TripMapper tripMapper;
        private final TicketMapper ticketMapper;

        // Crea un nuevo viaje validando que el bus esté disponible
        @Override
        @Transactional
        public TripResponse createTrip(TripCreateRequest request) {
                Route route = routeRepository.findById(request.routeId())
                                .orElseThrow(() -> new ResourceNotFoundException("Ruta", request.routeId()));

                Bus bus = busRepository.findById(request.busId())
                                .orElseThrow(() -> new ResourceNotFoundException("Bus", request.busId()));

                if (bus.getStatus() != Bus.BusStatus.ACTIVE) {
                        throw new BusinessException("El bus no está disponible", HttpStatus.BAD_REQUEST,
                                        "BUS_NOT_AVAILABLE");
                }

                Trip trip = tripMapper.toEntity(request);
                // Establecer las relaciones manualmente
                trip.setRoute(route);
                trip.setBus(bus);

                Trip savedTrip = tripRepository.save(trip);

                return tripMapper.toResponse(savedTrip);
        }

        // Busca viajes por ruta y/o fecha (filtros opcionales)
        @Override
        @Transactional(readOnly = true)
        public List<TripResponse> searchTrips(Long routeId, LocalDate date) {
                List<Trip> trips;

                if (routeId != null && date != null) {
                        trips = tripRepository.findByRouteIdAndTripDate(routeId, date);
                } else if (routeId != null) {
                        trips = tripRepository.findAll().stream()
                                        .filter(t -> t.getRoute().getId().equals(routeId))
                                        .toList();
                } else if (date != null) {
                        trips = tripRepository.findAll().stream()
                                        .filter(t -> t.getTripDate().equals(date))
                                        .toList();
                } else {
                        trips = tripRepository.findAll();
                }

                return tripMapper.toResponseList(trips);
        }

        // Obtiene los detalles completos de un viaje por su ID
        @Override
        @Transactional(readOnly = true)
        public TripDetailResponse getTripById(Long id) {
                Trip trip = tripRepository.findByIdWithDetails(id)
                                .orElseThrow(() -> new ResourceNotFoundException("Viaje", id));

                // Mapear la entidad a DTO básico
                TripDetailResponse basicResponse = tripMapper.toDetailResponse(trip);

                // Calcular los campos que faltan
                Long soldSeatsCount = ticketRepository.countSoldSeats(trip.getId());
                Integer capacity = trip.getBus().getCapacity();
                Integer availableSeatsCount = capacity - soldSeatsCount.intValue();
                Double occupancy = capacity > 0 ? (soldSeatsCount.doubleValue() / capacity) * 100.0 : 0.0;

                // Calcular números de asientos disponibles
                List<Integer> availableSeatNumbers = new ArrayList<>();
                for (int seatNum = 1; seatNum <= capacity; seatNum++) {
                        // Verificar si el asiento está vendido para cualquier tramo del viaje
                        boolean isSold = ticketRepository.existsByTripIdAndSeatNumberAndStatus(
                                        trip.getId(),
                                        seatNum,
                                        Ticket.TicketStatus.SOLD);
                        if (!isSold) {
                                availableSeatNumbers.add(seatNum);
                        }
                }

                // Crear el response completo con todos los campos calculados
                return new TripDetailResponse(
                                basicResponse.id(),
                                basicResponse.route(),
                                basicResponse.bus(),
                                basicResponse.tripDate(),
                                basicResponse.departureTime(),
                                basicResponse.arrivalEta(),
                                basicResponse.status(),
                                basicResponse.assignment(),
                                soldSeatsCount.intValue(),
                                availableSeatsCount,
                                occupancy,
                                availableSeatNumbers);
        }

        // Obtiene el estado de disponibilidad de todos los asientos para un tramo
        // específico
        @Override
        @Transactional(readOnly = true)
        public List<SeatStatusResponse> getSeatAvailability(Long tripId, Long fromStopId, Long toStopId) {
                Trip trip = tripRepository.findById(tripId)
                                .orElseThrow(() -> new ResourceNotFoundException("Viaje", tripId));

                Stop fromStop = stopRepository.findById(fromStopId)
                                .orElseThrow(() -> new ResourceNotFoundException("Parada origen", fromStopId));

                Stop toStop = stopRepository.findById(toStopId)
                                .orElseThrow(() -> new ResourceNotFoundException("Parada destino", toStopId));

                if (!fromStop.getRoute().getId().equals(trip.getRoute().getId()) ||
                                !toStop.getRoute().getId().equals(trip.getRoute().getId())) {
                        throw new BusinessException("Las paradas no pertenecen a la ruta del viaje",
                                        HttpStatus.BAD_REQUEST,
                                        "INVALID_STOPS");
                }

                if (fromStop.getOrder() >= toStop.getOrder()) {
                        throw new BusinessException("La parada de origen debe ser anterior a la de destino",
                                        HttpStatus.BAD_REQUEST,
                                        "INVALID_SEGMENT");
                }

                Integer capacity = trip.getBus().getCapacity();
                List<SeatStatusResponse> seatStatuses = new ArrayList<>();

                // Obtener los órdenes de las paradas
                Integer fromStopOrder = fromStop.getOrder();
                Integer toStopOrder = toStop.getOrder();

                for (Integer seatNumber = 1; seatNumber <= capacity; seatNumber++) {
                        Boolean isAvailable = ticketRepository.isSeatAvailableForSegment(
                                        tripId, seatNumber, fromStopOrder, toStopOrder);

                        seatStatuses.add(new SeatStatusResponse(
                                        seatNumber,
                                        isAvailable,
                                        isAvailable ? "AVAILABLE" : "OCCUPIED"));
                }

                return seatStatuses;
        }

        // Actualiza el estado de un viaje validando transiciones permitidas
        @Override
        @Transactional
        public TripResponse updateTripStatus(Long id, Trip.TripStatus status) {
                Trip trip = tripRepository.findById(id)
                                .orElseThrow(() -> new ResourceNotFoundException("Viaje", id));

                validateStatusTransition(trip.getStatus(), status);

                if (status == Trip.TripStatus.CANCELLED) {
                        releaseTicketsAndHolds(trip);
                }

                trip.setStatus(status);
                Trip updatedTrip = tripRepository.save(trip);

                return tripMapper.toResponse(updatedTrip);
        }

        // Cancela un viaje si aún no ha partido o llegado
        @Override
        @Transactional
        public void cancelTrip(Long id) {
                Trip trip = tripRepository.findById(id)
                                .orElseThrow(() -> new ResourceNotFoundException("Viaje", id));

                if (trip.getStatus() == Trip.TripStatus.DEPARTED ||
                                trip.getStatus() == Trip.TripStatus.ARRIVED) {
                        throw new BusinessException("No se puede cancelar un viaje que ya partió o llegó",
                                        HttpStatus.BAD_REQUEST,
                                        "INVALID_CANCEL");
                }

                if (trip.getStatus() == Trip.TripStatus.CANCELLED) {
                        throw new BusinessException("El viaje ya está cancelado",
                                        HttpStatus.BAD_REQUEST,
                                        "INVALID_CANCEL");
                }

                releaseTicketsAndHolds(trip);

                trip.setStatus(Trip.TripStatus.CANCELLED);
                tripRepository.save(trip);
        }

        // Al cancelar un viaje se cancelan sus tickets vendidos (reembolso total, la cancelación es de la empresa)
        // y se liberan los holds activos
        private void releaseTicketsAndHolds(Trip trip) {
                List<Ticket> soldTickets = ticketRepository.findByTripIdAndStatus(trip.getId(), Ticket.TicketStatus.SOLD);
                for (Ticket ticket : soldTickets) {
                        ticket.setStatus(Ticket.TicketStatus.CANCELLED);
                }
                ticketRepository.saveAll(soldTickets);

                List<SeatHold> activeHolds = seatHoldRepository.findActiveHoldsByTrip(trip.getId(), LocalDateTime.now());
                for (SeatHold hold : activeHolds) {
                        hold.setStatus(SeatHold.HoldStatus.EXPIRED);
                }
                seatHoldRepository.saveAll(activeHolds);
        }

        // Obtiene la lista de pasajeros que viajan en un tramo específico
        @Override
        @Transactional(readOnly = true)
        public List<TicketResponse> getPassengersBySegment(Long tripId, Long fromStopId, Long toStopId) {
                Trip trip = tripRepository.findById(tripId)
                                .orElseThrow(() -> new ResourceNotFoundException("Viaje", tripId));

                Stop fromStop = stopRepository.findById(fromStopId)
                                .orElseThrow(() -> new ResourceNotFoundException("Parada origen", fromStopId));

                Stop toStop = stopRepository.findById(toStopId)
                                .orElseThrow(() -> new ResourceNotFoundException("Parada destino", toStopId));

                if (!fromStop.getRoute().getId().equals(trip.getRoute().getId()) ||
                                !toStop.getRoute().getId().equals(trip.getRoute().getId())) {
                        throw new BusinessException("Las paradas no pertenecen a la ruta del viaje",
                                        HttpStatus.BAD_REQUEST,
                                        "INVALID_STOPS");
                }

                if (fromStop.getOrder() >= toStop.getOrder()) {
                        throw new BusinessException("La parada de origen debe ser anterior a la de destino",
                                        HttpStatus.BAD_REQUEST,
                                        "INVALID_SEGMENT");
                }

                // Todos los pasajeros a bordo en algún punto del tramo, no solo los de origen/destino exactos
                List<Ticket> tickets = ticketRepository.findTicketsBySegment(tripId, fromStop.getOrder(),
                                toStop.getOrder());
                return ticketMapper.toResponseList(tickets);
        }

        // Valida que la transición de estado del viaje sea permitida
        private void validateStatusTransition(Trip.TripStatus currentStatus, Trip.TripStatus newStatus) {
                boolean isValidTransition = switch (currentStatus) {
                        case SCHEDULED ->
                                newStatus == Trip.TripStatus.BOARDING || newStatus == Trip.TripStatus.CANCELLED;
                        case BOARDING ->
                                newStatus == Trip.TripStatus.DEPARTED || newStatus == Trip.TripStatus.CANCELLED;
                        case DEPARTED -> newStatus == Trip.TripStatus.ARRIVED;
                        case ARRIVED, CANCELLED -> false;
                };

                if (!isValidTransition) {
                        throw new BusinessException(
                                        String.format("Transición de estado inválida: %s -> %s", currentStatus,
                                                        newStatus),
                                        HttpStatus.BAD_REQUEST,
                                        "INVALID_STATE_TRANSITION");
                }
        }
}
