package com.web.service.trip;

import com.web.dto.ticket.TicketResponse;
import com.web.dto.ticket.mapper.TicketMapper;
import com.web.dto.trip.TripCreateRequest;
import com.web.dto.trip.TripDetailResponse;
import com.web.dto.trip.TripResponse;
import com.web.dto.trip.TripUpdateRequest;
import com.web.dto.trip.SeatAvailabilityResponse;
import com.web.dto.trip.SeatStatusResponse;
import com.web.dto.trip.SegmentOccupancyResponse;
import com.web.dto.trip.mapper.TripMapper;
import com.web.entity.Bus;
import com.web.entity.Incident;
import com.web.entity.Parcel;
import com.web.entity.Route;
import com.web.entity.Seat;
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
import com.web.repository.SeatRepository;
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
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TripServiceImpl implements TripService {

        private final TripRepository tripRepository;
        private final RouteRepository routeRepository;
        private final BusRepository busRepository;
        private final StopRepository stopRepository;
        private final TicketRepository ticketRepository;
        private final SeatHoldRepository seatHoldRepository;
        private final SeatRepository seatRepository;
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

                LocalDateTime departure = request.departureTime();
                if (!departure.isAfter(LocalDateTime.now())) {
                        throw new BusinessException("La salida del viaje debe ser posterior al momento actual",
                                        HttpStatus.BAD_REQUEST, "INVALID_DATES");
                }

                // Sin llegada estimada se calcula con la duración de la ruta
                LocalDateTime arrival = request.arrivalEta();
                if (arrival == null) {
                        if (route.getDurationMin() == null) {
                                throw new BusinessException("Debe indicar la llegada estimada: la ruta no tiene duración",
                                                HttpStatus.BAD_REQUEST, "INVALID_DATES");
                        }
                        arrival = departure.plusMinutes(route.getDurationMin());
                }

                if (!departure.toLocalDate().equals(request.tripDate()) || !arrival.isAfter(departure)) {
                        throw new BusinessException(
                                        "La fecha del viaje debe coincidir con la salida y la llegada debe ser posterior a la salida",
                                        HttpStatus.BAD_REQUEST, "INVALID_DATES");
                }

                // El bus puede hacer varios viajes el mismo día mientras las franjas horarias no se solapen
                if (tripRepository.existsOverlappingTripForBus(bus.getId(), departure, arrival, null)) {
                        throw new BusinessException("El bus ya tiene otro viaje que se solapa con ese horario",
                                        HttpStatus.CONFLICT, "BUS_BUSY");
                }

                Trip trip = tripMapper.toEntity(request);
                // Establecer las relaciones manualmente
                trip.setRoute(route);
                trip.setBus(bus);
                trip.setArrivalEta(arrival);

                Trip savedTrip = tripRepository.save(trip);

                return tripMapper.toResponse(savedTrip);
        }

        // Busca salidas por ruta y/o fecha (filtros opcionales). Por defecto solo las reservables
        // (SCHEDULED/BOARDING con salida futura); ADMIN y DISPATCHER pueden pedir todas con includeAll
        @Override
        @Transactional(readOnly = true)
        public List<TripResponse> searchTrips(Long routeId, LocalDate date, boolean includeAll) {
                boolean all = includeAll && (SecurityUtils.hasRole("ADMIN") || SecurityUtils.hasRole("DISPATCHER"));
                List<Trip> trips = tripRepository.searchTrips(routeId, date, all, LocalDateTime.now());
                return tripMapper.toResponseList(trips);
        }

        // Obtiene los detalles completos de un viaje por su ID
        @Override
        @Transactional(readOnly = true)
        public TripDetailResponse getTripById(Long id) {
                Trip trip = tripRepository.findByIdWithDetails(id)
                                .orElseThrow(() -> new ResourceNotFoundException("Viaje", id));

                // El mapper ya calcula sillas vendidas, disponibles y ocupación
                TripDetailResponse basicResponse = tripMapper.toDetailResponse(trip);

                // Números de silla disponibles para el viaje completo: sin ticket SOLD en ningún tramo.
                // Una silla vendida solo en un tramo no aparece aquí; el mapa por tramo es GET /trips/{id}/seats
                Set<Integer> soldSeatNumbers = ticketRepository.findByTripIdAndStatus(trip.getId(), Ticket.TicketStatus.SOLD)
                                .stream()
                                .map(Ticket::getSeatNumber)
                                .collect(Collectors.toSet());
                // Sillas vendibles: las físicas más las de overbooking aprobadas por el DISPATCHER
                int sellableSeats = trip.getBus().getCapacity() + approvedOverbookingSeats(trip);
                List<Integer> availableSeatNumbers = new ArrayList<>();
                for (int seatNum = 1; seatNum <= sellableSeats; seatNum++) {
                        if (!soldSeatNumbers.contains(seatNum)) {
                                availableSeatNumbers.add(seatNum);
                        }
                }

                return new TripDetailResponse(
                                basicResponse.id(),
                                basicResponse.route(),
                                basicResponse.bus(),
                                basicResponse.tripDate(),
                                basicResponse.departureTime(),
                                basicResponse.arrivalEta(),
                                basicResponse.status(),
                                basicResponse.assignment(),
                                basicResponse.soldSeats(),
                                basicResponse.availableSeats(),
                                basicResponse.occupancyPercentage(),
                                availableSeatNumbers,
                                basicResponse.platform(),
                                basicResponse.departedAt(),
                                basicResponse.arrivedAt());
        }

        // Mapa de sillas para un tramo: una consulta de tickets y otra de holds solapados, resueltas en memoria
        @Override
        @Transactional(readOnly = true)
        public SeatAvailabilityResponse getSeatAvailability(Long tripId, Long fromStopId, Long toStopId) {
                Trip trip = tripRepository.findById(tripId)
                                .orElseThrow(() -> new ResourceNotFoundException("Viaje", tripId));

                // Un viaje cancelado o que ya llegó no admite ventas: no tiene mapa de sillas
                if (trip.getStatus() == Trip.TripStatus.CANCELLED || trip.getStatus() == Trip.TripStatus.ARRIVED) {
                        throw new InvalidStateTransitionException(
                                        "No hay mapa de sillas para un viaje en estado " + trip.getStatus());
                }

                Stop[] segment = validateSegment(trip, fromStopId, toStopId);
                int fromStopOrder = segment[0].getOrder();
                int toStopOrder = segment[1].getOrder();

                // Sillas vendidas en un tramo que se solapa con el pedido
                Set<Integer> occupiedSeats = ticketRepository.findTicketsBySegment(tripId, fromStopOrder, toStopOrder)
                                .stream()
                                .map(Ticket::getSeatNumber)
                                .collect(Collectors.toSet());

                // Holds activos que se solapan con el tramo: la silla se muestra HELD (no disponible)
                Set<Integer> heldSeats = seatHoldRepository.findActiveHoldsByTrip(tripId, LocalDateTime.now())
                                .stream()
                                .filter(h -> holdOverlaps(h, fromStopOrder, toStopOrder))
                                .map(SeatHold::getSeatNumber)
                                .collect(Collectors.toSet());

                // Tipo de cada silla física; las de overbooking (sin fila en seats) se muestran STANDARD
                Map<Integer, Seat.SeatType> seatTypes = seatRepository.findByBusIdOrderBySeatNumberAsc(trip.getBus().getId())
                                .stream()
                                .collect(Collectors.toMap(Seat::getSeatNumber, Seat::getSeatType, (a, b) -> a));

                int sellableSeats = trip.getBus().getCapacity() + approvedOverbookingSeats(trip);
                List<SeatStatusResponse> seatStatuses = new ArrayList<>();
                int availableCount = 0;
                for (int seatNumber = 1; seatNumber <= sellableSeats; seatNumber++) {
                        boolean occupied = occupiedSeats.contains(seatNumber);
                        boolean held = !occupied && heldSeats.contains(seatNumber);
                        boolean available = !occupied && !held;
                        if (available) {
                                availableCount++;
                        }
                        String status = occupied ? "OCCUPIED" : held ? "HELD" : "AVAILABLE";
                        String seatType = seatTypes.getOrDefault(seatNumber, Seat.SeatType.STANDARD).name();
                        seatStatuses.add(new SeatStatusResponse(seatNumber, available, status, seatType));
                }

                return new SeatAvailabilityResponse(tripId, fromStopId, toStopId, sellableSeats, availableCount,
                                seatStatuses);
        }

        // Ocupación en tiempo real por tramos consecutivos Stop[i] → Stop[i+1] de la ruta (panel de despacho)
        @Override
        @Transactional(readOnly = true)
        public List<SegmentOccupancyResponse> getOccupancyBySegment(Long tripId) {
                Trip trip = tripRepository.findById(tripId)
                                .orElseThrow(() -> new ResourceNotFoundException("Viaje", tripId));

                List<Stop> stops = stopRepository.findByRouteIdOrderByOrderAsc(trip.getRoute().getId());
                int capacity = trip.getBus().getCapacity();

                List<SegmentOccupancyResponse> segments = new ArrayList<>();
                for (int i = 0; i < stops.size() - 1; i++) {
                        Stop from = stops.get(i);
                        Stop to = stops.get(i + 1);
                        Long sold = ticketRepository.countSoldSeatsForSegment(tripId, from.getOrder(), to.getOrder());
                        int soldSeats = sold == null ? 0 : sold.intValue();
                        double occupancy = capacity > 0 ? (soldSeats * 100.0) / capacity : 0.0;
                        segments.add(new SegmentOccupancyResponse(from.getId(), from.getName(), to.getId(), to.getName(),
                                        soldSeats, capacity, occupancy));
                }
                return segments;
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

                // Un viaje que ya partió, llegó o está cancelado no admite la transición (422)
                if (trip.getStatus() == Trip.TripStatus.DEPARTED
                                || trip.getStatus() == Trip.TripStatus.ARRIVED
                                || trip.getStatus() == Trip.TripStatus.CANCELLED) {
                        throw new InvalidStateTransitionException(trip.getStatus().name(),
                                        Trip.TripStatus.CANCELLED.name());
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

                Stop[] segment = validateSegment(trip, fromStopId, toStopId);
                Stop fromStop = segment[0];
                Stop toStop = segment[1];

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
                // Aplica también si solo se cambia el bus o la llegada: un viaje cuya salida ya pasó no se reprograma
                if (!departure.isAfter(LocalDateTime.now())) {
                        throw new BusinessException("La salida del viaje debe ser posterior al momento actual",
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

                // El bus (nuevo o el mismo) no puede tener otro viaje que se solape con la nueva franja
                LocalDate newDate = departure.toLocalDate();
                boolean busChanged = !bus.getId().equals(trip.getBus().getId());
                boolean scheduleChanged = !departure.equals(trip.getDepartureTime()) || !arrival.equals(trip.getArrivalEta());
                if ((busChanged || scheduleChanged)
                                && tripRepository.existsOverlappingTripForBus(bus.getId(), departure, arrival, trip.getId())) {
                        throw new BusinessException("El bus ya tiene otro viaje que se solapa con ese horario",
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

        // Valida que las paradas existan, pertenezcan a la ruta del viaje y formen un tramo hacia adelante
        private Stop[] validateSegment(Trip trip, Long fromStopId, Long toStopId) {
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
                return new Stop[] { fromStop, toStop };
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
