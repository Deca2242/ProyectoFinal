package com.web.repository;

import com.web.entity.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// Cubre las consultas de repositorio que no tenían test y la migración V3 (estado SOLD en seat_holds)
@DisplayName("Consultas adicionales de repositorios")
class AdditionalQueriesRepositoryTest extends BaseRepositoryTest {

    @Autowired
    private TestEntityManager em;

    @Autowired
    private TicketRepository ticketRepository;

    @Autowired
    private TripRepository tripRepository;

    @Autowired
    private RouteRepository routeRepository;

    @Autowired
    private StopRepository stopRepository;

    @Autowired
    private ParcelRepository parcelRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SeatHoldRepository seatHoldRepository;

    private Route route;
    private Stop stopA;
    private Stop stopB;
    private Stop stopC;
    private Bus bus;
    private Trip trip;
    private User passenger;
    private LocalDate tripDate;

    @BeforeEach
    void setUp() {
        tripDate = LocalDate.now().plusDays(2);

        route = em.persist(Route.builder()
                .code("TST-001")
                .name("Santa Marta - Barranquilla")
                .origin("Santa Marta")
                .destination("Barranquilla")
                .distanceKm(new BigDecimal("100.00"))
                .durationMin(120)
                .isActive(true)
                .build());

        stopA = em.persist(Stop.builder().route(route).name("Terminal Santa Marta").order(1).build());
        stopB = em.persist(Stop.builder().route(route).name("Ciénaga").order(2).build());
        stopC = em.persist(Stop.builder().route(route).name("Terminal Barranquilla").order(3).build());

        bus = em.persist(Bus.builder()
                .plate("TST123")
                .capacity(40)
                .amenities(new HashMap<>())
                .status(Bus.BusStatus.ACTIVE)
                .build());

        trip = em.persist(Trip.builder()
                .route(route)
                .bus(bus)
                .tripDate(tripDate)
                .departureTime(tripDate.atTime(10, 0))
                .arrivalEta(tripDate.atTime(12, 0))
                .status(Trip.TripStatus.SCHEDULED)
                .build());

        passenger = em.persist(user("pasajero@test.com", User.Role.PASSENGER, User.Status.ACTIVE));
        em.flush();
    }

    // ---------- TicketRepository ----------

    @Test
    @DisplayName("isSeatAvailableForFullTrip: false si el asiento tiene cualquier ticket SOLD en el viaje")
    void ticket_isSeatAvailableForFullTrip() {
        persistTicket(5, stopB, stopC, Ticket.TicketStatus.SOLD, Ticket.PaymentMethod.CASH, "50000");
        persistTicket(6, stopA, stopC, Ticket.TicketStatus.CANCELLED, Ticket.PaymentMethod.CASH, "50000");
        em.flush();

        assertThat(ticketRepository.isSeatAvailableForFullTrip(trip.getId(), 5)).isFalse();
        // Un ticket cancelado no bloquea el asiento
        assertThat(ticketRepository.isSeatAvailableForFullTrip(trip.getId(), 6)).isTrue();
        assertThat(ticketRepository.isSeatAvailableForFullTrip(trip.getId(), 7)).isTrue();
    }

    @Test
    @DisplayName("isSeatAvailableForSegment usa el ORDEN de las paradas: tramos contiguos no se solapan")
    void ticket_isSeatAvailableForSegment_usesStopOrder() {
        // Asiento vendido en B(2) -> C(3)
        persistTicket(5, stopB, stopC, Ticket.TicketStatus.SOLD, Ticket.PaymentMethod.CASH, "50000");
        em.flush();

        // A(1) -> C(3) se solapa con B -> C
        assertThat(ticketRepository.isSeatAvailableForSegment(
                trip.getId(), 5, stopA.getOrder(), stopC.getOrder())).isFalse();
        // A(1) -> B(2) es contiguo, no se solapa
        assertThat(ticketRepository.isSeatAvailableForSegment(
                trip.getId(), 5, stopA.getOrder(), stopB.getOrder())).isTrue();
    }

    @Test
    @DisplayName("findTicketsBySegment: tickets SOLD a bordo en algún punto del tramo (por orden de parada)")
    void ticket_findTicketsBySegment() {
        persistTicket(1, stopA, stopB, Ticket.TicketStatus.SOLD, Ticket.PaymentMethod.CASH, "20000");
        persistTicket(2, stopA, stopB, Ticket.TicketStatus.CANCELLED, Ticket.PaymentMethod.CASH, "20000");
        persistTicket(3, stopA, stopC, Ticket.TicketStatus.SOLD, Ticket.PaymentMethod.CASH, "40000");
        persistTicket(4, stopB, stopC, Ticket.TicketStatus.SOLD, Ticket.PaymentMethod.CASH, "20000");
        em.flush();

        // Tramo A -> B: el de A -> C también va a bordo; el de B -> C sube justo cuando termina el tramo
        assertThat(ticketRepository.findTicketsBySegment(trip.getId(), stopA.getOrder(), stopB.getOrder()))
                .extracting(Ticket::getSeatNumber).containsExactly(1, 3);
        // Tramo B -> C
        assertThat(ticketRepository.findTicketsBySegment(trip.getId(), stopB.getOrder(), stopC.getOrder()))
                .extracting(Ticket::getSeatNumber).containsExactly(3, 4);
    }

    @Test
    @DisplayName("findByTripIdAndStatus: tickets de un viaje filtrados por estado")
    void ticket_findByTripIdAndStatus() {
        persistTicket(1, stopA, stopB, Ticket.TicketStatus.SOLD, Ticket.PaymentMethod.CASH, "20000");
        persistTicket(2, stopA, stopB, Ticket.TicketStatus.CANCELLED, Ticket.PaymentMethod.CASH, "20000");
        em.flush();

        assertThat(ticketRepository.findByTripIdAndStatus(trip.getId(), Ticket.TicketStatus.SOLD))
                .extracting(Ticket::getSeatNumber).containsExactly(1);
    }

    @Test
    @DisplayName("findUnboardedTicketsDepartingBetween: solo SOLD sin abordar, de la primera parada, viaje en abordaje y en la ventana")
    void ticket_findUnboardedTicketsDepartingBetween() {
        LocalDateTime now = LocalDateTime.now();
        trip.setDepartureTime(now.plusMinutes(3));
        trip.setStatus(Trip.TripStatus.BOARDING);
        Ticket unboarded = persistTicket(1, stopA, stopC, Ticket.TicketStatus.SOLD, Ticket.PaymentMethod.CASH, "20000");
        Ticket boarded = persistTicket(2, stopA, stopC, Ticket.TicketStatus.SOLD, Ticket.PaymentMethod.CASH, "20000");
        boarded.setBoardedAt(now.minusMinutes(10));
        persistTicket(3, stopB, stopC, Ticket.TicketStatus.SOLD, Ticket.PaymentMethod.CASH, "20000");
        persistTicket(4, stopA, stopC, Ticket.TicketStatus.CANCELLED, Ticket.PaymentMethod.CASH, "20000");
        em.flush();

        assertThat(ticketRepository.findUnboardedTicketsDepartingBetween(now, now.plusMinutes(5)))
                .extracting(Ticket::getId).containsExactly(unboarded.getId());
        // Fuera de la ventana no se devuelve nada
        assertThat(ticketRepository.findUnboardedTicketsDepartingBetween(now, now.plusMinutes(1))).isEmpty();

        // Si el abordaje no se abrió (viaje retrasado o sin despachar) no se marca a nadie
        trip.setStatus(Trip.TripStatus.SCHEDULED);
        em.flush();
        assertThat(ticketRepository.findUnboardedTicketsDepartingBetween(now, now.plusMinutes(5))).isEmpty();
    }

    @Test
    @DisplayName("findSoldCashTicketsPurchasedBetween: solo efectivo, SOLD y dentro del rango de compra")
    void ticket_findSoldCashTicketsPurchasedBetween() {
        LocalDate day = LocalDate.now();
        // purchased_at no es actualizable: se fija antes de persistir
        persistTicket(1, Ticket.TicketStatus.SOLD, Ticket.PaymentMethod.CASH, day.atTime(9, 0));
        persistTicket(2, Ticket.TicketStatus.SOLD, Ticket.PaymentMethod.CARD, day.atTime(9, 0));
        persistTicket(3, Ticket.TicketStatus.CANCELLED, Ticket.PaymentMethod.CASH, day.atTime(9, 0));
        persistTicket(4, Ticket.TicketStatus.SOLD, Ticket.PaymentMethod.CASH, day.minusDays(1).atTime(23, 59));
        persistTicket(5, Ticket.TicketStatus.SOLD, Ticket.PaymentMethod.CASH, day.plusDays(1).atStartOfDay());
        em.flush();

        List<Ticket> result = ticketRepository.findSoldCashTicketsPurchasedBetween(
                day.atStartOfDay(), day.plusDays(1).atStartOfDay());

        assertThat(result).extracting(Ticket::getSeatNumber).containsExactly(1);
    }

    @Test
    @DisplayName("findCashTicketsPurchasedBetween: efectivo comprado en el rango, en cualquier estado")
    void ticket_findCashTicketsPurchasedBetween() {
        LocalDate day = LocalDate.now();
        persistTicket(1, Ticket.TicketStatus.SOLD, Ticket.PaymentMethod.CASH, day.atTime(9, 0));
        persistTicket(2, Ticket.TicketStatus.SOLD, Ticket.PaymentMethod.CARD, day.atTime(9, 0));
        persistTicket(3, Ticket.TicketStatus.CANCELLED, Ticket.PaymentMethod.CASH, day.atTime(10, 0));
        persistTicket(4, Ticket.TicketStatus.NO_SHOW, Ticket.PaymentMethod.CASH, day.atTime(11, 0));
        persistTicket(5, Ticket.TicketStatus.SOLD, Ticket.PaymentMethod.CASH, day.plusDays(1).atStartOfDay());
        em.flush();

        List<Ticket> result = ticketRepository.findCashTicketsPurchasedBetween(
                day.atStartOfDay(), day.plusDays(1).atStartOfDay());

        assertThat(result).extracting(Ticket::getSeatNumber).containsExactly(1, 3, 4);
    }

    @Test
    @DisplayName("findCashTicketsCancelledBetween: efectivo cancelado en el rango (por fecha de cancelación)")
    void ticket_findCashTicketsCancelledBetween() {
        LocalDate day = LocalDate.now();
        Ticket today = persistTicket(1, Ticket.TicketStatus.CANCELLED, Ticket.PaymentMethod.CASH, day.minusDays(3).atTime(9, 0));
        today.setCancelledAt(day.atTime(12, 0));
        Ticket yesterday = persistTicket(2, Ticket.TicketStatus.CANCELLED, Ticket.PaymentMethod.CASH, day.minusDays(3).atTime(9, 0));
        yesterday.setCancelledAt(day.minusDays(1).atTime(12, 0));
        Ticket card = persistTicket(3, Ticket.TicketStatus.CANCELLED, Ticket.PaymentMethod.CARD, day.minusDays(3).atTime(9, 0));
        card.setCancelledAt(day.atTime(12, 0));
        em.flush();

        assertThat(ticketRepository.findCashTicketsCancelledBetween(day.atStartOfDay(), day.plusDays(1).atStartOfDay()))
                .extracting(Ticket::getSeatNumber).containsExactly(1);
    }

    @Test
    @DisplayName("countSoldSeatsForSegment: sillas distintas vendidas que se solapan con el tramo")
    void ticket_countSoldSeatsForSegment() {
        persistTicket(1, stopA, stopB, Ticket.TicketStatus.SOLD, Ticket.PaymentMethod.CASH, "20000");
        persistTicket(2, stopA, stopC, Ticket.TicketStatus.SOLD, Ticket.PaymentMethod.CASH, "40000");
        persistTicket(3, stopB, stopC, Ticket.TicketStatus.CANCELLED, Ticket.PaymentMethod.CASH, "20000");
        em.flush();

        // A -> B: sillas 1 y 2; B -> C: solo la 2 (la 3 está cancelada y la 1 baja en B)
        assertThat(ticketRepository.countSoldSeatsForSegment(trip.getId(), 1, 2)).isEqualTo(2L);
        assertThat(ticketRepository.countSoldSeatsForSegment(trip.getId(), 2, 3)).isEqualTo(1L);
    }

    @Test
    @DisplayName("findUnboardedOriginTickets: vendidos sin abordar que suben en la primera parada")
    void ticket_findUnboardedOriginTickets() {
        Ticket unboarded = persistTicket(1, stopA, stopC, Ticket.TicketStatus.SOLD, Ticket.PaymentMethod.CASH, "20000");
        Ticket boarded = persistTicket(2, stopA, stopC, Ticket.TicketStatus.SOLD, Ticket.PaymentMethod.CASH, "20000");
        boarded.setBoardedAt(LocalDateTime.now());
        persistTicket(3, stopB, stopC, Ticket.TicketStatus.SOLD, Ticket.PaymentMethod.CASH, "20000");
        em.flush();

        assertThat(ticketRepository.findUnboardedOriginTickets(trip.getId()))
                .extracting(Ticket::getId).containsExactly(unboarded.getId());
    }

    @Test
    @DisplayName("findByTripDateBetween: tickets de viajes en el rango de fechas")
    void ticket_findByTripDateBetween() {
        persistTicket(1, stopA, stopC, Ticket.TicketStatus.SOLD, Ticket.PaymentMethod.CASH, "20000");
        em.flush();

        assertThat(ticketRepository.findByTripDateBetween(tripDate, tripDate)).hasSize(1);
        assertThat(ticketRepository.findByTripDateBetween(tripDate.plusDays(1), tripDate.plusDays(2))).isEmpty();
    }

    @Test
    @DisplayName("lockById: bloquea y devuelve el id del viaje (vacío si no existe)")
    void trip_lockById() {
        assertThat(tripRepository.lockById(trip.getId())).contains(trip.getId());
        assertThat(tripRepository.lockById(-1L)).isEmpty();
    }

    @Test
    @DisplayName("findCashTicketsInTimeRange: tickets en efectivo del usuario por hora de salida")
    void ticket_findCashTicketsInTimeRange() {
        persistTicket(1, stopA, stopC, Ticket.TicketStatus.SOLD, Ticket.PaymentMethod.CASH, "50000");
        persistTicket(2, stopA, stopC, Ticket.TicketStatus.SOLD, Ticket.PaymentMethod.QR, "50000");
        em.flush();

        List<Ticket> result = ticketRepository.findCashTicketsInTimeRange(
                passenger.getId(), tripDate.atStartOfDay(), tripDate.plusDays(1).atStartOfDay());
        List<Ticket> outOfRange = ticketRepository.findCashTicketsInTimeRange(
                passenger.getId(), tripDate.plusDays(1).atStartOfDay(), tripDate.plusDays(2).atStartOfDay());

        assertThat(result).extracting(Ticket::getSeatNumber).containsExactly(1);
        assertThat(outOfRange).isEmpty();
    }

    @Test
    @DisplayName("Métricas: conteo de vendidos, no-show e ingresos por viaje")
    void ticket_metrics() {
        persistTicket(1, stopA, stopC, Ticket.TicketStatus.SOLD, Ticket.PaymentMethod.CASH, "50000");
        persistTicket(2, stopA, stopB, Ticket.TicketStatus.SOLD, Ticket.PaymentMethod.CARD, "20000");
        persistTicket(3, stopA, stopC, Ticket.TicketStatus.NO_SHOW, Ticket.PaymentMethod.CASH, "50000");
        persistTicket(4, stopA, stopC, Ticket.TicketStatus.CANCELLED, Ticket.PaymentMethod.CASH, "50000");
        em.flush();

        assertThat(ticketRepository.countSoldTicketsInRange(tripDate, tripDate)).isEqualTo(2L);
        assertThat(ticketRepository.countSoldTicketsInRange(tripDate.plusDays(1), tripDate.plusDays(3))).isZero();
        assertThat(ticketRepository.countNoShowsInRange(tripDate, tripDate)).isEqualTo(1L);
        assertThat(ticketRepository.calculateRevenueByTrip(trip.getId())).isEqualByComparingTo("70000");
    }

    @Test
    @DisplayName("existsByTripIdAndSeatNumberAndStatus")
    void ticket_existsByTripIdAndSeatNumberAndStatus() {
        persistTicket(8, stopA, stopC, Ticket.TicketStatus.SOLD, Ticket.PaymentMethod.CASH, "50000");
        em.flush();

        assertThat(ticketRepository.existsByTripIdAndSeatNumberAndStatus(trip.getId(), 8, Ticket.TicketStatus.SOLD)).isTrue();
        assertThat(ticketRepository.existsByTripIdAndSeatNumberAndStatus(trip.getId(), 8, Ticket.TicketStatus.CANCELLED)).isFalse();
        assertThat(ticketRepository.existsByTripIdAndSeatNumberAndStatus(trip.getId(), 9, Ticket.TicketStatus.SOLD)).isFalse();
    }

    // ---------- TripRepository ----------

    @Test
    @DisplayName("countPendingTripsByRoute: solo cuenta viajes SCHEDULED/BOARDING con salida futura")
    void trip_countPendingTripsByRoute() {
        LocalDate today = LocalDate.now();
        persistTrip(today.minusDays(3));
        Trip cancelled = persistTrip(today.plusDays(4));
        cancelled.setStatus(Trip.TripStatus.CANCELLED);
        em.flush();

        // Solo el viaje del setUp (en 2 días) sigue pendiente
        assertThat(tripRepository.countPendingTripsByRoute(route.getId(), LocalDateTime.now())).isEqualTo(1L);
        assertThat(tripRepository.countPendingTripsByRoute(route.getId(), LocalDateTime.now().plusDays(10))).isZero();
    }

    // ---------- RouteRepository / StopRepository ----------

    @Test
    @DisplayName("existsByCode")
    void route_existsByCode() {
        assertThat(routeRepository.existsByCode("TST-001")).isTrue();
        assertThat(routeRepository.existsByCode("NO-EXISTE")).isFalse();
    }

    @Test
    @DisplayName("findRoutesConnecting: respeta el sentido del recorrido y solo rutas activas")
    void route_findRoutesConnecting() {
        // Coincidencia parcial y sin distinguir mayúsculas
        assertThat(routeRepository.findRoutesConnecting("santa marta", "barranquilla"))
                .extracting(Route::getCode).containsExactly("TST-001");
        // Sentido contrario: no hay ruta
        assertThat(routeRepository.findRoutesConnecting("Barranquilla", "Santa Marta")).isEmpty();

        route.setIsActive(false);
        em.flush();
        assertThat(routeRepository.findRoutesConnecting("Santa Marta", "Barranquilla")).isEmpty();
    }

    @Test
    @DisplayName("existsByRouteIdAndOrder")
    void stop_existsByRouteIdAndOrder() {
        assertThat(stopRepository.existsByRouteIdAndOrder(route.getId(), 2)).isTrue();
        assertThat(stopRepository.existsByRouteIdAndOrder(route.getId(), 4)).isFalse();
    }

    // ---------- ParcelRepository ----------

    @Test
    @DisplayName("Encomiendas por teléfono, estado y rango de fechas del viaje")
    void parcel_queries() {
        persistParcel("PCL-1", "3001", "3101", Parcel.ParcelStatus.IN_TRANSIT);
        persistParcel("PCL-2", "3002", "3101", Parcel.ParcelStatus.CREATED);
        em.flush();

        assertThat(parcelRepository.findBySenderPhone("3001")).extracting(Parcel::getCode).containsExactly("PCL-1");
        assertThat(parcelRepository.findByReceiverPhone("3101")).hasSize(2);
        assertThat(parcelRepository.findByTripIdAndStatus(trip.getId(), Parcel.ParcelStatus.IN_TRANSIT))
                .extracting(Parcel::getCode).containsExactly("PCL-1");
        assertThat(parcelRepository.findByDateRange(tripDate, tripDate)).hasSize(2);
        assertThat(parcelRepository.findByDateRange(tripDate.plusDays(1), tripDate.plusDays(5))).isEmpty();
    }

    // ---------- UserRepository ----------

    @Test
    @DisplayName("findByRoleAndStatus")
    void user_findByRoleAndStatus() {
        em.persist(user("driver1@test.com", User.Role.DRIVER, User.Status.ACTIVE));
        em.persist(user("driver2@test.com", User.Role.DRIVER, User.Status.INACTIVE));
        em.flush();

        assertThat(userRepository.findByRoleAndStatus(User.Role.DRIVER, User.Status.ACTIVE))
                .extracting(User::getEmail).containsExactly("driver1@test.com");
        assertThat(userRepository.findByRoleAndStatus(User.Role.DRIVER, User.Status.INACTIVE))
                .extracting(User::getEmail).containsExactly("driver2@test.com");
    }

    // ---------- TripRepository ----------

    @Test
    @DisplayName("findBusIdsWithTripsOnDate: buses con viajes no cancelados ni llegados en la fecha")
    void trip_findBusIdsWithTripsOnDate() {
        Bus otherBus = em.persist(Bus.builder()
                .plate("OTR456")
                .capacity(40)
                .amenities(new HashMap<>())
                .status(Bus.BusStatus.ACTIVE)
                .build());
        Trip cancelled = persistTrip(tripDate);
        cancelled.setBus(otherBus);
        cancelled.setStatus(Trip.TripStatus.CANCELLED);
        // Un viaje que ya llegó tampoco ocupa el bus
        Bus arrivedBus = em.persist(Bus.builder()
                .plate("LLG789")
                .capacity(40)
                .amenities(new HashMap<>())
                .status(Bus.BusStatus.ACTIVE)
                .build());
        Trip arrived = persistTrip(tripDate);
        arrived.setBus(arrivedBus);
        arrived.setStatus(Trip.TripStatus.ARRIVED);
        em.flush();

        assertThat(tripRepository.findBusIdsWithTripsOnDate(tripDate)).containsExactly(bus.getId());
        assertThat(tripRepository.findBusIdsWithTripsOnDate(tripDate.plusDays(1))).isEmpty();
    }

    // ---------- SeatHoldRepository ----------

    @Test
    @DisplayName("findOverlappingActiveHolds: holds por tramo solo se solapan si comparten parte del recorrido")
    void seatHold_findOverlappingActiveHolds() {
        LocalDateTime now = LocalDateTime.now();
        SeatHold segmentHold = em.persist(SeatHold.builder()
                .trip(trip).seatNumber(8).user(passenger)
                .fromStop(stopA).toStop(stopB)
                .expiresAt(now.plusMinutes(10))
                .status(SeatHold.HoldStatus.HOLD)
                .build());
        em.persist(SeatHold.builder()
                .trip(trip).seatNumber(8).user(passenger)
                .fromStop(stopB).toStop(stopC)
                .expiresAt(now.minusMinutes(1)) // expirado
                .status(SeatHold.HoldStatus.HOLD)
                .build());
        SeatHold fullTripHold = em.persist(SeatHold.builder()
                .trip(trip).seatNumber(9).user(passenger)
                .expiresAt(now.plusMinutes(10))
                .status(SeatHold.HoldStatus.HOLD)
                .build());
        em.flush();

        // A -> B se solapa con el hold A -> B
        assertThat(seatHoldRepository.findOverlappingActiveHolds(trip.getId(), 8, 1, 2, now))
                .extracting(SeatHold::getId).containsExactly(segmentHold.getId());
        // B -> C es contiguo a A -> B y el hold B -> C está expirado
        assertThat(seatHoldRepository.findOverlappingActiveHolds(trip.getId(), 8, 2, 3, now)).isEmpty();
        // Un hold sin tramo bloquea cualquier tramo
        assertThat(seatHoldRepository.findOverlappingActiveHolds(trip.getId(), 9, 2, 3, now))
                .extracting(SeatHold::getId).containsExactly(fullTripHold.getId());
        // findActiveHolds ignora el tramo
        assertThat(seatHoldRepository.findActiveHolds(trip.getId(), 8, now)).hasSize(1);
    }

    // ---------- Migración V3 ----------

    @Test
    @DisplayName("V3: un hold puede guardarse con estado SOLD (antes lo impedía un CHECK de la BD)")
    void seatHold_canBePersistedAsSold() {
        SeatHold hold = em.persist(SeatHold.builder()
                .trip(trip)
                .seatNumber(12)
                .user(passenger)
                .expiresAt(LocalDateTime.now().plusMinutes(10))
                .status(SeatHold.HoldStatus.HOLD)
                .build());
        em.flush();

        hold.setStatus(SeatHold.HoldStatus.SOLD);
        em.flush();
        em.clear();

        assertThat(seatHoldRepository.findById(hold.getId()))
                .get()
                .extracting(SeatHold::getStatus)
                .isEqualTo(SeatHold.HoldStatus.SOLD);
    }

    // ---------- Helpers ----------

    private User user(String email, User.Role role, User.Status status) {
        return User.builder()
                .name("Usuario " + email)
                .email(email)
                .phone("3000000000")
                .role(role)
                .status(status)
                .passwordHash("$2a$10$hash")
                .build();
    }

    private Ticket persistTicket(int seat, Stop from, Stop to, Ticket.TicketStatus status,
                                 Ticket.PaymentMethod method, String price) {
        return em.persist(Ticket.builder()
                .trip(trip)
                .passenger(passenger)
                .seatNumber(seat)
                .fromStop(from)
                .toStop(to)
                .price(new BigDecimal(price))
                .paymentMethod(method)
                .status(status)
                .qrCode("QR-" + seat + "-" + status)
                .build());
    }

    private Ticket persistTicket(int seat, Ticket.TicketStatus status, Ticket.PaymentMethod method,
                                 LocalDateTime purchasedAt) {
        return em.persist(Ticket.builder()
                .trip(trip)
                .passenger(passenger)
                .seatNumber(seat)
                .fromStop(stopA)
                .toStop(stopC)
                .price(new BigDecimal("50000"))
                .paymentMethod(method)
                .status(status)
                .qrCode("QR-CASH-" + seat)
                .purchasedAt(purchasedAt)
                .build());
    }

    private Trip persistTrip(LocalDate date) {
        return em.persist(Trip.builder()
                .route(route)
                .bus(bus)
                .tripDate(date)
                .departureTime(date.atTime(15, 0))
                .arrivalEta(date.atTime(17, 0))
                .status(Trip.TripStatus.SCHEDULED)
                .build());
    }

    private Parcel persistParcel(String code, String senderPhone, String receiverPhone, Parcel.ParcelStatus status) {
        return em.persist(Parcel.builder()
                .code(code)
                .trip(trip)
                .senderName("Remitente")
                .senderPhone(senderPhone)
                .receiverName("Destinatario")
                .receiverPhone(receiverPhone)
                .fromStop(stopA)
                .toStop(stopC)
                .price(new BigDecimal("15000"))
                .status(status)
                .build());
    }
}
