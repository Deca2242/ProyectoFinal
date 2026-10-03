package com.web.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.dto.auth.Login.LoginRequest;
import com.web.dto.auth.Login.RegisterRequest;
import com.web.dto.catalog.Bus.BusCreateRequest;
import com.web.dto.catalog.Bus.BusUpdateRequest;
import com.web.dto.catalog.Seat.SeatUpdateRequest;
import com.web.dto.catalog.Stop.StopCreateRequest;
import com.web.dto.catalog.Stop.StopUpdateRequest;
import com.web.dto.trip.TripCreateRequest;
import com.web.entity.*;
import com.web.integration.userstories.BaseIntegrationTest;
import com.web.repository.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Catálogo, búsqueda de salidas, mapa de sillas, ocupación por tramo y flota (HTTP + JWT + BD)
class CatalogTripRulesIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper om;

    @Autowired
    private RouteRepository routeRepository;

    @Autowired
    private StopRepository stopRepository;

    @Autowired
    private BusRepository busRepository;

    @Autowired
    private SeatRepository seatRepository;

    @Autowired
    private TripRepository tripRepository;

    @Autowired
    private TicketRepository ticketRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    private Route route;
    private Stop stopA;
    private Stop stopB;
    private Stop stopC;
    private Bus bus;
    private LocalDate date;

    @BeforeEach
    void setUp() {
        route = routeRepository.save(Route.builder()
                .code("CAT-" + (System.nanoTime() % 1_000_000_000L))
                .name("Santa Marta - Barranquilla")
                .origin("Santa Marta")
                .destination("Barranquilla")
                .distanceKm(new BigDecimal("100.00"))
                .durationMin(120)
                .isActive(true)
                .build());
        stopA = stopRepository.save(Stop.builder().route(route).name("Santa Marta").order(1).build());
        stopB = stopRepository.save(Stop.builder().route(route).name("Ciénaga").order(2).build());
        stopC = stopRepository.save(Stop.builder().route(route).name("Barranquilla").order(3).build());
        bus = newBus(40);
        date = LocalDate.now().plusDays(3);
        flushAndClear();
    }

    // ---------- Búsqueda de salidas ----------

    @Test
    void searchTrips_shouldReturnOnlyBookableTrips() throws Exception {
        // Given: salidas mezcladas en la misma ruta
        Trip scheduled = newTrip(newBus(40), date.atTime(8, 0), Trip.TripStatus.SCHEDULED);
        Trip boarding = newTrip(newBus(40), date.atTime(9, 0), Trip.TripStatus.BOARDING);
        Trip cancelled = newTrip(newBus(40), date.atTime(10, 0), Trip.TripStatus.CANCELLED);
        LocalDateTime yesterday = LocalDateTime.now().minusDays(1);
        Trip arrived = newTrip(newBus(40), yesterday, Trip.TripStatus.ARRIVED);
        Trip pastScheduled = newTrip(newBus(40), yesterday.plusHours(1), Trip.TripStatus.SCHEDULED);
        flushAndClear();

        // When/Then: el público solo ve las salidas SCHEDULED/BOARDING con salida futura
        mvc.perform(get("/api/v1/trips").param("routeId", route.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value(scheduled.getId()))
                .andExpect(jsonPath("$[1].id").value(boarding.getId()));

        // includeAll lo ignora un pasajero...
        String paxToken = registerAndLogin("pax@cattest.com");
        mvc.perform(get("/api/v1/trips")
                        .header("Authorization", bearer(paxToken))
                        .param("routeId", route.getId().toString())
                        .param("includeAll", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));

        // ...pero un DISPATCHER ve todas las salidas
        String dispatcherToken = staff("dispatcher@cattest.com", User.Role.DISPATCHER);
        mvc.perform(get("/api/v1/trips")
                        .header("Authorization", bearer(dispatcherToken))
                        .param("routeId", route.getId().toString())
                        .param("includeAll", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(5)))
                .andExpect(jsonPath("$[*].id").value(containsInAnyOrder(
                        scheduled.getId().intValue(), boarding.getId().intValue(), cancelled.getId().intValue(),
                        arrived.getId().intValue(), pastScheduled.getId().intValue())));
    }

    @Test
    void shouldSearchTrips_PopulateSoldAndAvailableSeats() throws Exception {
        // Given: 2 sillas vendidas en un bus de 40 con 2 de overbooking aprobado
        Trip trip = newTrip(bus, date.atTime(8, 0), Trip.TripStatus.SCHEDULED);
        trip.setOverbookingApprovedSeats(2);
        trip.setPlatform("A3");
        User passenger = passenger("buyer@cattest.com");
        sellTicket(trip, passenger, 1, stopA, stopC);
        sellTicket(trip, passenger, 2, stopA, stopB);
        flushAndClear();

        // When/Then
        mvc.perform(get("/api/v1/trips")
                        .param("routeId", route.getId().toString())
                        .param("date", date.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].soldSeats").value(2))
                .andExpect(jsonPath("$[0].availableSeats").value(40))
                .andExpect(jsonPath("$[0].occupancyPercentage").value(5.0))
                .andExpect(jsonPath("$[0].platform").value("A3"));

        mvc.perform(get("/api/v1/trips/{id}", trip.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.soldSeats").value(2))
                .andExpect(jsonPath("$.availableSeats").value(40))
                .andExpect(jsonPath("$.availableSeatNumbers", hasSize(40)))
                .andExpect(jsonPath("$.platform").value("A3"));
    }

    // ---------- Ocupación por tramo y mapa de sillas ----------

    @Test
    void occupancyBySegment_shouldCountEachSegment() throws Exception {
        // Given: la silla 1 se vende A→B y B→C (dos pasajeros); la silla 2 se vende A→C
        Trip trip = newTrip(bus, date.atTime(8, 0), Trip.TripStatus.SCHEDULED);
        sellTicket(trip, passenger("p1@cattest.com"), 1, stopA, stopB);
        sellTicket(trip, passenger("p2@cattest.com"), 1, stopB, stopC);
        sellTicket(trip, passenger("p3@cattest.com"), 2, stopA, stopC);
        flushAndClear();
        String dispatcherToken = staff("dispatcher@cattest.com", User.Role.DISPATCHER);

        // When/Then: cada tramo cuenta la silla 1 una sola vez
        mvc.perform(get("/api/v1/trips/{id}/occupancy", trip.getId())
                        .header("Authorization", bearer(dispatcherToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].fromStopId").value(stopA.getId()))
                .andExpect(jsonPath("$[0].toStopId").value(stopB.getId()))
                .andExpect(jsonPath("$[0].soldSeats").value(2))
                .andExpect(jsonPath("$[0].capacity").value(40))
                .andExpect(jsonPath("$[0].occupancyPercentage").value(5.0))
                .andExpect(jsonPath("$[1].fromStopName").value("Ciénaga"))
                .andExpect(jsonPath("$[1].soldSeats").value(2));

        // Un pasajero no puede ver la ocupación
        String paxToken = registerAndLogin("pax@cattest.com");
        mvc.perform(get("/api/v1/trips/{id}/occupancy", trip.getId())
                        .header("Authorization", bearer(paxToken)))
                .andExpect(status().isForbidden());
    }

    @Test
    void seatMap_shouldExposeSeatTypeAndSegmentAvailability() throws Exception {
        // Given: bus creado por la API (genera sillas) con la silla 2 preferencial
        String adminToken = staff("admin@cattest.com", User.Role.ADMIN);
        long busId = createBus(adminToken, "map 001", 3);
        mvc.perform(put("/api/v1/buses/{id}/seats/{seat}", busId, 2)
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new SeatUpdateRequest(Seat.SeatType.PREFERENTIAL))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seatType").value("PREFERENTIAL"));
        Trip trip = newTrip(busRepository.findById(busId).orElseThrow(), date.atTime(8, 0), Trip.TripStatus.SCHEDULED);
        sellTicket(trip, passenger("p1@cattest.com"), 1, stopA, stopB);
        flushAndClear();

        // When/Then: el tramo B→C no se solapa con la venta A→B
        mvc.perform(get("/api/v1/trips/{id}/seats", trip.getId())
                        .param("fromStopId", stopB.getId().toString())
                        .param("toStopId", stopC.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tripId").value(trip.getId()))
                .andExpect(jsonPath("$.totalSeats").value(3))
                .andExpect(jsonPath("$.availableSeats").value(3))
                .andExpect(jsonPath("$.seats[1].seatType").value("PREFERENTIAL"))
                .andExpect(jsonPath("$.seats[0].seatType").value("STANDARD"));

        mvc.perform(get("/api/v1/trips/{id}/seats", trip.getId())
                        .param("fromStopId", stopA.getId().toString())
                        .param("toStopId", stopC.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableSeats").value(2))
                .andExpect(jsonPath("$.seats[0].status").value("OCCUPIED"));
    }

    @Test
    void seatMap_onCancelledTrip_shouldReturn422() throws Exception {
        // Given
        Trip trip = newTrip(bus, date.atTime(8, 0), Trip.TripStatus.CANCELLED);
        flushAndClear();

        // When/Then
        mvc.perform(get("/api/v1/trips/{id}/seats", trip.getId())
                        .param("fromStopId", stopA.getId().toString())
                        .param("toStopId", stopC.getId().toString()))
                .andExpect(status().isUnprocessableEntity());
    }

    // ---------- Creación y cancelación de viajes ----------

    @Test
    void createTrip_shouldValidateDatesAndBusOverlapByHours() throws Exception {
        String adminToken = staff("admin@cattest.com", User.Role.ADMIN);
        // El bus ya hizo un viaje en la mañana (06:00-08:00) que llegó
        newTrip(bus, date.atTime(6, 0), Trip.TripStatus.ARRIVED);
        // y tiene otro programado 12:00-14:00
        newTrip(bus, date.atTime(12, 0), Trip.TripStatus.SCHEDULED);
        flushAndClear();

        // Segundo viaje el mismo día en una franja libre (sin llegada: se calcula con los 120 min de la ruta)
        mvc.perform(post("/api/v1/trips")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new TripCreateRequest(route.getId(), bus.getId(), date,
                                date.atTime(15, 0), null))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.arrivalEta").value(date.atTime(17, 0).toString() + ":00"))
                .andExpect(jsonPath("$.soldSeats").value(0))
                .andExpect(jsonPath("$.availableSeats").value(40));

        // Franja que se solapa con el viaje de las 12:00 → 409
        mvc.perform(post("/api/v1/trips")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new TripCreateRequest(route.getId(), bus.getId(), date,
                                date.atTime(13, 0), date.atTime(14, 30)))))
                .andExpect(status().isConflict());

        // Salida en el pasado → 400
        LocalDateTime past = LocalDateTime.now().minusHours(2);
        mvc.perform(post("/api/v1/trips")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new TripCreateRequest(route.getId(), bus.getId(),
                                past.toLocalDate(), past, past.plusHours(1)))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cancelTrip_shouldReturn422WhenDeparted() throws Exception {
        // Given
        String adminToken = staff("admin@cattest.com", User.Role.ADMIN);
        Trip trip = newTrip(bus, LocalDateTime.now().minusHours(1), Trip.TripStatus.DEPARTED);
        flushAndClear();

        // When/Then
        mvc.perform(delete("/api/v1/trips/{id}", trip.getId())
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isUnprocessableEntity());
        assertThat(tripRepository.findById(trip.getId()).orElseThrow().getStatus()).isEqualTo(Trip.TripStatus.DEPARTED);
    }

    // ---------- Rutas y paradas ----------

    @Test
    void inactiveRoutes_shouldBeHiddenFromPublicAndVisibleToAdmin() throws Exception {
        // Given
        Route inactive = routeRepository.save(Route.builder()
                .code("INA-" + (System.nanoTime() % 1_000_000_000L))
                .name("Ruta inactiva").origin("A").destination("B")
                .distanceKm(BigDecimal.TEN).durationMin(30).isActive(false)
                .build());
        flushAndClear();
        String adminToken = staff("admin@cattest.com", User.Role.ADMIN);

        // When/Then: el público no la ve
        String publicList = mvc.perform(get("/api/v1/routes"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(ids(publicList)).contains(route.getId()).doesNotContain(inactive.getId());
        mvc.perform(get("/api/v1/routes/{id}", inactive.getId())).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/routes/{id}/stops", inactive.getId())).andExpect(status().isNotFound());
        // includeInactive sin ser ADMIN no tiene efecto
        mvc.perform(get("/api/v1/routes/{id}", inactive.getId()).param("includeInactive", "true"))
                .andExpect(status().isNotFound());

        // Un ADMIN con includeInactive la ve
        String adminList = mvc.perform(get("/api/v1/routes")
                        .header("Authorization", bearer(adminToken))
                        .param("includeInactive", "true"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(ids(adminList)).contains(route.getId(), inactive.getId());
        mvc.perform(get("/api/v1/routes/{id}", inactive.getId())
                        .header("Authorization", bearer(adminToken))
                        .param("includeInactive", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isActive").value(false));
    }

    @Test
    void deleteRoute_withPendingTrips_shouldReturn409() throws Exception {
        // Given
        String adminToken = staff("admin@cattest.com", User.Role.ADMIN);
        newTrip(bus, date.atTime(8, 0), Trip.TripStatus.SCHEDULED);
        flushAndClear();

        // When/Then
        mvc.perform(delete("/api/v1/routes/{id}", route.getId())
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isConflict());
    }

    @Test
    void stops_shouldKeepContiguousOrder() throws Exception {
        String adminToken = staff("admin@cattest.com", User.Role.ADMIN);

        // Orden 0 o con hueco → 400; el siguiente (4) → 201
        mvc.perform(post("/api/v1/routes/{id}/stops", route.getId())
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new StopCreateRequest(null, "Cero", 0, null, null))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/routes/{id}/stops", route.getId())
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new StopCreateRequest(null, "Hueco", 6, null, null))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/routes/{id}/stops", route.getId())
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new StopCreateRequest(route.getId() + 1, "Otra ruta", 4, null, null))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/routes/{id}/stops", route.getId())
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new StopCreateRequest(null, "Soledad", 4, null, null))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.stops", hasSize(4)));

        // Se renombra una parada
        mvc.perform(put("/api/v1/routes/{routeId}/stops/{stopId}", route.getId(), stopB.getId())
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new StopUpdateRequest("Ciénaga Centro", null, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Ciénaga Centro"))
                .andExpect(jsonPath("$.order").value(2));

        // Al borrar la parada 2 las siguientes se corren: el orden sigue siendo 1..3
        mvc.perform(delete("/api/v1/routes/{routeId}/stops/{stopId}", route.getId(), stopB.getId())
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isNoContent());
        flushAndClear();
        assertThat(stopRepository.findByRouteIdOrderByOrderAsc(route.getId()))
                .extracting(Stop::getOrder)
                .containsExactly(1, 2, 3);
    }

    // ---------- Flota y sillas ----------

    @Test
    void createBus_shouldGenerateSeats() throws Exception {
        // Given
        String adminToken = staff("admin@cattest.com", User.Role.ADMIN);

        // When: placa con minúsculas y espacios
        long busId = createBus(adminToken, " cat 777 ", 4);

        // Then: placa normalizada y sillas 1..4 STANDARD
        mvc.perform(get("/api/v1/buses/{id}", busId).header("Authorization", bearer(adminToken)))
                .andExpect(jsonPath("$.plate").value("CAT777"));
        mvc.perform(get("/api/v1/buses/plate/{plate}", "cat777").header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/buses/{id}/seats", busId).header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(4)))
                .andExpect(jsonPath("$[3].seatNumber").value(4))
                .andExpect(jsonPath("$[3].seatType").value("STANDARD"));

        // La placa normalizada choca con otra escrita distinto
        mvc.perform(post("/api/v1/buses")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new BusCreateRequest("CAT 777", 10, null))))
                .andExpect(status().isConflict());

        // Al ampliar y reducir la capacidad las sillas se sincronizan
        updateBus(adminToken, busId, new BusUpdateRequest(6, null, null)).andExpect(status().isOk());
        flushAndClear();
        assertThat(seatRepository.findByBusIdOrderBySeatNumberAsc(busId)).extracting(Seat::getSeatNumber)
                .containsExactly(1, 2, 3, 4, 5, 6);
        updateBus(adminToken, busId, new BusUpdateRequest(2, null, null)).andExpect(status().isOk());
        flushAndClear();
        assertThat(seatRepository.findByBusIdOrderBySeatNumberAsc(busId)).extracting(Seat::getSeatNumber)
                .containsExactly(1, 2);
    }

    @Test
    void updateBus_reduceCapacityBelowSoldSeat_shouldReturn409() throws Exception {
        // Given: silla 30 vendida en un viaje futuro
        String adminToken = staff("admin@cattest.com", User.Role.ADMIN);
        Trip trip = newTrip(bus, date.atTime(8, 0), Trip.TripStatus.SCHEDULED);
        sellTicket(trip, passenger("p1@cattest.com"), 30, stopA, stopC);
        flushAndClear();

        // When/Then: 29 → 409; 30 → OK
        updateBus(adminToken, bus.getId(), new BusUpdateRequest(29, null, null)).andExpect(status().isConflict());
        updateBus(adminToken, bus.getId(), new BusUpdateRequest(30, null, null)).andExpect(status().isOk());
    }

    @Test
    void deleteBus_withFutureTrips_shouldReturn409_andRetireOtherwise() throws Exception {
        // Given
        String adminToken = staff("admin@cattest.com", User.Role.ADMIN);
        Bus idle = newBus(20);
        newTrip(bus, date.atTime(8, 0), Trip.TripStatus.SCHEDULED);
        flushAndClear();

        // When/Then: con viajes programados no se puede retirar ni mandar a mantenimiento
        mvc.perform(delete("/api/v1/buses/{id}", bus.getId()).header("Authorization", bearer(adminToken)))
                .andExpect(status().isConflict());
        updateBus(adminToken, bus.getId(), new BusUpdateRequest(null, null, Bus.BusStatus.MAINTENANCE))
                .andExpect(status().isConflict());
        assertThat(busRepository.findById(bus.getId()).orElseThrow().getStatus()).isEqualTo(Bus.BusStatus.ACTIVE);

        // Sin viajes pendientes se retira (RETIRED)
        mvc.perform(delete("/api/v1/buses/{id}", idle.getId()).header("Authorization", bearer(adminToken)))
                .andExpect(status().isNoContent());
        flushAndClear();
        assertThat(busRepository.findById(idle.getId()).orElseThrow().getStatus()).isEqualTo(Bus.BusStatus.RETIRED);
    }

    @Test
    void availableBuses_byTimeSlot_shouldExcludeOnlyOverlappingBuses() throws Exception {
        // Given: el bus tiene un viaje 08:00-10:00
        String dispatcherToken = staff("dispatcher@cattest.com", User.Role.DISPATCHER);
        newTrip(bus, date.atTime(8, 0), Trip.TripStatus.SCHEDULED);
        flushAndClear();

        // When/Then: libre por la tarde, ocupado a las 9
        String afternoon = mvc.perform(get("/api/v1/buses/available")
                        .header("Authorization", bearer(dispatcherToken))
                        .param("departureTime", date.atTime(14, 0).toString())
                        .param("arrivalEta", date.atTime(16, 0).toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(ids(afternoon)).contains(bus.getId());

        String morning = mvc.perform(get("/api/v1/buses/available")
                        .header("Authorization", bearer(dispatcherToken))
                        .param("departureTime", date.atTime(9, 0).toString())
                        .param("arrivalEta", date.atTime(11, 0).toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(ids(morning)).doesNotContain(bus.getId());
    }

    // ---------- Utilidades ----------

    private Bus newBus(int capacity) {
        return busRepository.save(Bus.builder()
                .plate("C" + (System.nanoTime() % 100_000_000L))
                .capacity(capacity)
                .amenities(new HashMap<>())
                .status(Bus.BusStatus.ACTIVE)
                .build());
    }

    private Trip newTrip(Bus tripBus, LocalDateTime departure, Trip.TripStatus status) {
        return tripRepository.save(Trip.builder()
                .route(route)
                .bus(tripBus)
                .tripDate(departure.toLocalDate())
                .departureTime(departure)
                .arrivalEta(departure.plusHours(2))
                .status(status)
                .build());
    }

    private User passenger(String email) {
        return userRepository.save(User.builder()
                .name("Pasajero").email(email).phone("300")
                .role(User.Role.PASSENGER).passwordHash("x").status(User.Status.ACTIVE)
                .build());
    }

    private void sellTicket(Trip trip, User passenger, int seat, Stop from, Stop to) {
        ticketRepository.save(Ticket.builder()
                .trip(trip).passenger(passenger).seatNumber(seat)
                .fromStop(from).toStop(to)
                .price(new BigDecimal("20000")).paymentMethod(Ticket.PaymentMethod.CASH)
                .status(Ticket.TicketStatus.SOLD)
                .qrCode("QR-CAT-" + System.nanoTime())
                .build());
    }

    private long createBus(String adminToken, String plate, int capacity) throws Exception {
        String body = mvc.perform(post("/api/v1/buses")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new BusCreateRequest(plate, capacity, null))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return om.readTree(body).get("id").asLong();
    }

    private org.springframework.test.web.servlet.ResultActions updateBus(String adminToken, Long busId,
                                                                         BusUpdateRequest request) throws Exception {
        return mvc.perform(put("/api/v1/buses/{id}", busId)
                .header("Authorization", bearer(adminToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(request)));
    }

    private List<Long> ids(String jsonArray) throws Exception {
        List<Long> ids = new ArrayList<>();
        for (JsonNode node : om.readTree(jsonArray)) {
            ids.add(node.get("id").asLong());
        }
        return ids;
    }

    private String staff(String email, User.Role role) throws Exception {
        createUser(new RegisterRequest("Staff " + role, email, "300", "secreto1", role));
        return login(email, "secreto1");
    }

    private String registerAndLogin(String email) throws Exception {
        mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(
                                new RegisterRequest("Pasajero", email, "300", "secreto1", User.Role.PASSENGER))))
                .andExpect(status().isCreated());
        return login(email, "secreto1");
    }

    private String login(String email, String password) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new LoginRequest(email, password))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return om.readTree(body).get("token").asText();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
