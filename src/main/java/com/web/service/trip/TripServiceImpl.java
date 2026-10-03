package com.web.service.trip;

import com.web.dto.ticket.TicketResponse;
import com.web.dto.ticket.mapper.TicketMapper;
import com.web.dto.trip.TripCreateRequest;
import com.web.dto.trip.TripDetailResponse;
import com.web.dto.trip.TripResponse;
import com.web.dto.trip.TripUpdateRequest;
import com.web.dto.trip.SeatAvailabilityResponse;
import com.web.dto.trip.SeatStatusResponse;
import com.web.dto.trip.mapper.TripMapper;
import com.web.entity.Bus;
import com.web.entity.Incident;
import com.web.entity.Parcel;
import com.web.entity.Route;
import com.web.entity.SeatHold;
import com.web.entity.Stop;
import com.web.entity.Ticket;
import com.web.entity.Trip;
import com.web.exception.BusinessException;
import com.web.exception.InvalidStateTransitionException;
import com.web.exception.ResourceNotFoundException;
import com.web.repository.AssignmentRepository;
import com.web.repository.BusRepository;
import com.web.repository.IncidentRepository;
import com.web.repository.ParcelRepository;
import com.web.repository.RouteRepository;
import com.web.repository.SeatHoldRepository;
import com.web.repository.StopRepository;
import com.web.repository.TicketRepository;
import com.web.repository.TripRepository;
import com.web.service.notification.NotificationService;
import com.web.util.SecurityUtils;
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
        private final AssignmentRepository assignmentRepository;
        private final ParcelRepository parcelRepository;
        private final IncidentRepository incidentRepository;
        private final TripMapper tripMapper;
        private final TicketMapper ticketMapper;
        private final NotificationService notificationService;

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

                if (Boolean.FALSE.equals(route.getIsActive())) {
                        throw new BusinessException("La ruta está inactiva", HttpStatus.BAD_REQUEST,
                                        "ROUTE_INACTIVE");
                }

                if (!request.departureTime().toLocalDate().equals(request.tripDate())
                                || !request.arrivalEta().isAfter(request.departureTime())) {
                        throw new BusinessException(
                                        "La fecha del viaje debe coincidir con la salida y la llegada debe ser posterior a la salida",
                                        HttpStatus.BAD_REQUEST, "INVALID_DATES");
                }

                if (tripRepository.findBusIdsWithTripsOnDate(request.tripDate()).contains(bus.getId())) {
                        throw new BusinessException("El bus ya tiene un viaje programado ese día",
                                        HttpStatus.CONFLICT, "BUS_BUSY");
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
                // Sillas vendibles: las físicas más las de overbooking aprobadas por el DISPATCHER
                int sellableSeats = capacity + approvedOverbookingSeats(trip);
                Integer availableSeatsCount = Math.max(0, sellableSeats - soldSeatsCount.intValue());
                Double occupancy = capacity > 0 ? (soldSeatsCount.doubleValue() / capacity) * 100.0 : 0.0;

                // Calcular números de asientos disponibles
                List<Integer> availableSeatNumbers = new ArrayList<>();
                for (int seatNum = 1; seatNum <= sellableSeats; seatNum++) {
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

                // Holds activos que se solapan con el tramo: la silla se muestra HELD (no disponible)
                List<SeatHold> activeHolds = seatHoldRepository.findActiveHoldsByTrip(tripId, LocalDateTime.now());

                int sellableSeats = capacity + approvedOverbookingSeats(trip);
                for (Integer seatNumber = 1; seatNumber <= sellableSeats; seatNumber++) {
                        Boolean isAvailable = ticketRepository.isSeatAvailableForSegment(
                                        tripId, seatNumber, fromStopOrder, toStopOrder);
                        final int seat = seatNumber;
                        boolean held = isAvailable && activeHolds.stream()
                                        .anyMatch(h -> h.getSeatNumber() == seat
                                                        && holdOverlaps(h, fromStopOrder, toStopOrder));

                        String status = !isAvailable ? "OCCUPIED" : held ? "HELD" : "AVAILABLE";
                        seatStatuses.add(new SeatStatusResponse(seatNumber, isAvailable && !held, status));
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

                // Abrir abordaje y dar salida tienen sus propias validaciones (asignación, checklist,
                // conductor, no-show): deben hacerse por los endpoints de despacho
                if (status == Trip.TripStatus.BOARDING || status == Trip.TripStatus.DEPARTED) {
                        throw new InvalidStateTransitionException(
                                        "El estado " + status + " se asigna desde los endpoints de despacho (boarding/depart)");
                }

                if (status == Trip.TripStatus.CANCELLED) {
                        releaseTicketsAndHolds(trip);
                }

                if (status == Trip.TripStatus.ARRIVED) {
                        trip.setArrivedAt(LocalDateTime.now());
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
                LocalDateTime now = LocalDateTime.now();
                // Aviso a los pasajeros antes de cancelar sus tickets (solo se notifica a los que siguen SOLD)
                notificationService.notifyTripCancelled(trip);
                List<Ticket> soldTickets = ticketRepository.findByTripIdAndStatus(trip.getId(), Ticket.TicketStatus.SOLD);
                for (Ticket ticket : soldTickets) {
                        ticket.setStatus(Ticket.TicketStatus.CANCELLED);
                        ticket.setRefundAmount(ticket.getPrice());
                        ticket.setCancelledAt(now);
                }
                ticketRepository.saveAll(soldTickets);

                List<SeatHold> activeHolds = seatHoldRepository.findActiveHoldsByTrip(trip.getId(), now);
                for (SeatHold hold : activeHolds) {
                        hold.setStatus(SeatHold.HoldStatus.EXPIRED);
                }
                seatHoldRepository.saveAll(activeHolds);

                // Las encomiendas que aún no se entregaron quedan FAILED con un incidente para reasignarlas
                List<Parcel> pendingParcels = parcelRepository.findByTripId(trip.getId()).stream()
                                .filter(p -> p.getStatus() == Parcel.ParcelStatus.CREATED
                                                || p.getStatus() == Parcel.ParcelStatus.IN_TRANSIT)
                                .toList();
                for (Parcel parcel : pendingParcels) {
                        parcel.setStatus(Parcel.ParcelStatus.FAILED);
                        incidentRepository.save(Incident.builder()
                                        .entityType(Incident.EntityType.PARCEL)
                                        .entityId(parcel.getId())
                                        .incidentType(Incident.IncidentType.DELIVERY_FAIL)
                                        .description("Viaje " + trip.getId() + " cancelado: la encomienda "
                                                        + parcel.getCode() + " debe reasignarse")
                                        .createdAt(now)
                                        .build());
                }
                parcelRepository.saveAll(pendingParcels);
        }

        // Obtiene la lista de pasajeros que viajan en un tramo específico
        @Override
        @Transactional(readOnly = true)
        public List<TicketResponse> getPassengersBySegment(Long tripId, Long fromStopId, Long toStopId) {
                Trip trip = tripRepository.findById(tripId)
                                .orElseThrow(() -> new ResourceNotFoundException("Viaje", tripId));

                // Un conductor solo ve la lista de pasajeros de los viajes que tiene asignados
                if (SecurityUtils.hasRole("DRIVER")) {
                        String username = SecurityUtils.currentUsername().orElse("");
                        boolean assigned = assignmentRepository.findByTripId(tripId)
                                        .map(a -> a.getDriver() != null && username.equalsIgnoreCase(a.getDriver().getEmail()))
                                        .orElse(false);
                        if (!assigned) {
                                throw new BusinessException("El conductor no está asignado a este viaje",
                                                HttpStatus.FORBIDDEN, "DRIVER_NOT_ASSIGNED");
                        }
                }

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
                // La lista de pasajeros para conductor/despachador no incluye el email (dato personal innecesario)
                return ticketMapper.toResponseList(tickets).stream()
                                .map(TripServiceImpl::withoutEmail)
                                .toList();
        }

        // Reprograma un viaje SCHEDULED: salida, llegada y/o bus. La fecha del viaje sigue a la nueva salida
        @Override
        @Transactional
        public TripResponse rescheduleTrip(Long id, TripUpdateRequest request) {
                Trip trip = tripRepository.findById(id)
                                .orElseThrow(() -> new ResourceNotFoundException("Viaje", id));

                if (trip.getStatus() != Trip.TripStatus.SCHEDULED) {
                        throw new InvalidStateTransitionException(
                                        "Solo se puede reprogramar un viaje SCHEDULED (estado actual: " + trip.getStatus() + ")");
                }

                if (request.departureTime() == null && request.arrivalEta() == null && request.busId() == null) {
                        throw new BusinessException("Debe indicar la nueva salida, la llegada o el bus",
                                        HttpStatus.BAD_REQUEST, "NOTHING_TO_UPDATE");
                }

                LocalDateTime departure = request.departureTime() != null ? request.departureTime() : trip.getDepartureTime();
                LocalDateTime arrival = request.arrivalEta() != null ? request.arrivalEta() : trip.getArrivalEta();
                if (arrival == null || !arrival.isAfter(departure)) {
                        throw new BusinessException("La llegada debe ser posterior a la salida",
                                        HttpStatus.BAD_REQUEST, "INVALID_DATES");
                }
                if (request.departureTime() != null && !departure.isAfter(LocalDateTime.now())) {
                        throw new BusinessException("La nueva salida debe ser posterior al momento actual",
                                        HttpStatus.BAD_REQUEST, "INVALID_DATES");
                }

                Bus bus = request.busId() != null
                                ? busRepository.findById(request.busId())
                                                .orElseThrow(() -> new ResourceNotFoundException("Bus", request.busId()))
                                : trip.getBus();
                if (bus.getStatus() != Bus.BusStatus.ACTIVE) {
                        throw new BusinessException("El bus no está disponible", HttpStatus.BAD_REQUEST,
                                        "BUS_NOT_AVAILABLE");
                }

                // Con el mismo bus y el mismo día el único viaje del bus es este; en otro caso el bus debe estar libre
                LocalDate newDate = departure.toLocalDate();
                boolean busChanged = !bus.getId().equals(trip.getBus().getId());
                boolean dateChanged = !newDate.equals(trip.getTripDate());
                if ((busChanged || dateChanged)
                                && tripRepository.findBusIdsWithTripsOnDate(newDate).contains(bus.getId())) {
                        throw new BusinessException("El bus ya tiene un viaje programado ese día",
                                        HttpStatus.CONFLICT, "BUS_BUSY");
                }

                // Las sillas ya vendidas deben existir en el bus nuevo (capacidad + overbooking aprobado)
                if (busChanged) {
                        int sellableSeats = bus.getCapacity() + approvedOverbookingSeats(trip);
                        boolean seatOutOfRange = ticketRepository.findByTripIdAndStatus(trip.getId(), Ticket.TicketStatus.SOLD)
                                        .stream()
                                        .anyMatch(t -> t.getSeatNumber() > sellableSeats);
                        if (seatOutOfRange) {
                                throw new BusinessException("Hay tiquetes vendidos en sillas que no existen en el bus nuevo",
                                                HttpStatus.CONFLICT, "SEATS_EXCEED_CAPACITY");
                        }
                }

                // El conductor asignado debe seguir libre en el nuevo horario
                boolean scheduleChanged = !departure.equals(trip.getDepartureTime()) || !arrival.equals(trip.getArrivalEta());
                if (scheduleChanged) {
                        assignmentRepository.findByTripId(trip.getId())
                                        .filter(a -> a.getDriver() != null)
                                        .ifPresent(a -> {
                                                if (!assignmentRepository.isDriverAvailableExcludingTrip(
                                                                a.getDriver().getId(), trip.getId(), departure, arrival)) {
                                                        throw new BusinessException(
                                                                        "El conductor asignado tiene otro viaje en el nuevo horario",
                                                                        HttpStatus.CONFLICT, "DRIVER_NOT_AVAILABLE");
                                                }
                                        });
                }

                // Los holds activos sobre sillas que no existen en el bus nuevo se liberan
                if (busChanged) {
                        int sellableSeats = bus.getCapacity() + approvedOverbookingSeats(trip);
                        List<SeatHold> orphanHolds = seatHoldRepository.findActiveHoldsByTrip(trip.getId(), LocalDateTime.now())
                                        .stream()
                                        .filter(h -> h.getSeatNumber() > sellableSeats)
                                        .toList();
                        orphanHolds.forEach(h -> h.setStatus(SeatHold.HoldStatus.EXPIRED));
                        seatHoldRepository.saveAll(orphanHolds);
                }

                tripMapper.updateEntityFromRequest(request, trip);
                trip.setTripDate(newDate);
                trip.setBus(bus);
                Trip saved = tripRepository.save(trip);

                if (scheduleChanged) {
                        notificationService.notifyTripRescheduled(saved);
                }

                return tripMapper.toResponse(saved);
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

                // 422 según la tabla de errores estándar (estado inválido para la transición)
                if (!isValidTransition) {
                        throw new InvalidStateTransitionException(currentStatus.name(), newStatus.name());
                }
        }

        // Un hold sin tramo bloquea todo el viaje; uno con tramo solo si se solapa por orden de parada
        private boolean holdOverlaps(SeatHold hold, int fromOrder, int toOrder) {
                if (hold.getFromStop() == null || hold.getToStop() == null) {
                        return true;
                }
                return hold.getFromStop().getOrder() < toOrder && hold.getToStop().getOrder() > fromOrder;
        }

        private static TicketResponse withoutEmail(TicketResponse t) {
                return new TicketResponse(t.id(), t.tripId(), t.routeName(), t.tripDate(), t.departureTime(),
                                t.passengerId(), t.passengerName(), null, t.seatNumber(),
                                t.fromStopId(), t.fromStopName(), t.fromStopOrder(),
                                t.toStopId(), t.toStopName(), t.toStopOrder(),
                                t.price(), t.paymentMethod(), t.status(), t.qrCode(), t.purchasedAt(),
                                t.baggage(), t.boardedAt());
        }

        private int approvedOverbookingSeats(Trip trip) {
                return trip.getOverbookingApprovedSeats() == null ? 0 : trip.getOverbookingApprovedSeats();
        }
}
