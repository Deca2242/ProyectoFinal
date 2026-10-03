package com.web.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.dto.auth.Login.LoginRequest;
import com.web.dto.auth.Login.RegisterRequest;
import com.web.dto.dispatch.Assignment.AssignmentCreateRequest;
import com.web.dto.dispatch.Assignment.AssignmentUpdateRequest;
import com.web.dto.dispatch.OverbookingPolicy.OverbookingPolicyCreateRequest;
import com.web.dto.sync.BoardingSyncRequest;
import com.web.dto.sync.OfflineBoarding;
import com.web.dto.ticket.TicketCreateRequest;
import com.web.entity.*;
import com.web.integration.userstories.BaseIntegrationTest;
import com.web.repository.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Panel de despacho de extremo a extremo (HTTP + JWT + BD): asignación con bus y conductor, checklist con vigencia,
// abordaje con cierre real, salida y llegada del conductor asignado, políticas de overbooking, conflictos offline
// y tareas programadas registradas
class DispatchFlowIntegrationTest extends BaseIntegrationTest {

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
    private TripRepository tripRepository;

    @Autowired
    private TicketRepository ticketRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OverbookingPolicyRepository policyRepository;

    @Autowired
    private SyncConflictRepository syncConflictRepository;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private EntityManager entityManager;

    private Route route;
    private Stop stopA;
    private Stop stopC;
    private Bus bus;
    private Trip trip;

    @BeforeEach
    void setUp() {
        route = routeRepository.save(Route.builder()
                .code("DSP-" + System.nanoTime())
                .name("Santa Marta - Barranquilla")
                .origin("Santa Marta")
                .destination("Barranquilla")
                .distanceKm(new BigDecimal("100.00"))
                .durationMin(120)
                .isActive(true)
                .build());
        stopA = stopRepository.save(Stop.builder().route(route).name("Santa Marta").order(1).build());
        stopRepository.save(Stop.builder().route(route).name("Ciénaga").order(2).build());
        stopC = stopRepository.save(Stop.builder().route(route).name("Barranquilla").order(3).build());

        bus = busRepository.save(newBus(40));

        LocalDate date = LocalDate.now().plusDays(3);
        trip = tripRepository.save(Trip.builder()
                .route(route)
                .bus(bus)
                .tripDate(date)
                .departureTime(date.atTime(12, 0))
                .arrivalEta(date.atTime(14, 0))
                .status(Trip.TripStatus.SCHEDULED)
                .build());

        flushAndClear();
    }

    // ---------- Flujo completo del conductor ----------

    @Test
    void driverFlow_assignOpenBoardCloseDepartArrive_shouldRegisterTimesAndRejectOtherDriver() throws Exception {
        String paxToken = registerAndLogin("pax@test.com");
        Long boarded = ticketId(purchase(paxToken, userId("pax@test.com"), 1));
        Long missing = ticketId(purchase(paxToken, userId("pax@test.com"), 2));
        String driverToken = staff("driver@test.com", User.Role.DRIVER);
        String otherDriverToken = staff("driver2@test.com", User.Role.DRIVER);
        String dispatcherToken = staff("disp@test.com", User.Role.DISPATCHER);

        // Asignación: el despachador sale del token (sin dispatcherId en el body)
        assign(dispatcherToken, new AssignmentCreateRequest(trip.getId(), userId("driver@test.com"), null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.dispatcherId").value(userId("disp@test.com")));
        approveChecklist(dispatcherToken).andExpect(status().isOk());

        // Otro conductor no ve la asignación ni el equipaje de este viaje
        mvc.perform(get("/api/v1/trips/{id}/assignment", trip.getId()).header("Authorization", bearer(otherDriverToken)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/trips/{id}/baggage", trip.getId()).header("Authorization", bearer(otherDriverToken)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/trips/{id}/assignment", trip.getId()).header("Authorization", bearer(driverToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.soatValidOnTripDate").value(true))
                .andExpect(jsonPath("$.reviewValidOnTripDate").value(true));
        mvc.perform(get("/api/v1/trips/{id}/baggage", trip.getId()).header("Authorization", bearer(driverToken)))
                .andExpect(status().isOk());

        // Abrir abordaje y abordar
        boarding(dispatcherToken, "open").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("BOARDING"));
        board(driverToken, qr(boarded)).andExpect(status().isOk());

        // Cerrar abordaje: marca no-show y registra boardingClosedAt; repetirlo no cambia nada
        boarding(dispatcherToken, "close").andExpect(status().isOk());
        flushAndClear();
        LocalDateTime closedAt = tripRepository.findById(trip.getId()).orElseThrow().getBoardingClosedAt();
        assertThat(closedAt).isNotNull();
        assertThat(ticketRepository.findById(missing).orElseThrow().getStatus()).isEqualTo(Ticket.TicketStatus.NO_SHOW);
        assertThat(ticketRepository.findById(missing).orElseThrow().getNoShowFee()).isNotNull();
        boarding(dispatcherToken, "close").andExpect(status().isOk());
        flushAndClear();
        assertThat(tripRepository.findById(trip.getId()).orElseThrow().getBoardingClosedAt()).isEqualTo(closedAt);

        // Salida: solo el conductor asignado
        mvc.perform(post("/api/v1/trips/{id}/depart", trip.getId()).header("Authorization", bearer(otherDriverToken)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/trips/{id}/depart", trip.getId()).header("Authorization", bearer(driverToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DEPARTED"));
        flushAndClear();
        Trip departed = tripRepository.findById(trip.getId()).orElseThrow();
        assertThat(departed.getDepartedAt()).isNotNull();
        assertThat(departed.getBoardingClosedAt()).isEqualTo(closedAt);

        // Tras la salida no se modifica la asignación ni el andén (422)
        approveChecklist(dispatcherToken).andExpect(status().isUnprocessableEntity());
        mvc.perform(put("/api/v1/trips/{id}/platform", trip.getId()).header("Authorization", bearer(dispatcherToken))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"platform\":\"A1\"}"))
                .andExpect(status().isUnprocessableEntity());

        // Llegada: solo el conductor asignado
        mvc.perform(post("/api/v1/trips/{id}/arrive", trip.getId()).header("Authorization", bearer(otherDriverToken)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/trips/{id}/arrive", trip.getId()).header("Authorization", bearer(driverToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARRIVED"));
        flushAndClear();
        assertThat(tripRepository.findById(trip.getId()).orElseThrow().getArrivedAt()).isNotNull();

        // Un viaje que ya llegó no admite asignación (422)
        assign(dispatcherToken, new AssignmentCreateRequest(trip.getId(), userId("driver2@test.com"), null))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void openBoarding_withoutAssignment_shouldReturn409_andAssignIsAllowedWhileBoarding() throws Exception {
        String dispatcherToken = staff("disp@test.com", User.Role.DISPATCHER);
        staff("driver@test.com", User.Role.DRIVER);

        boarding(dispatcherToken, "open")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("conductor")));

        // Un viaje que quedó en BOARDING sin asignación (p. ej. cambio de estado del ADMIN) aún se puede asignar
        Trip boardingTrip = tripRepository.findById(trip.getId()).orElseThrow();
        boardingTrip.setStatus(Trip.TripStatus.BOARDING);
        tripRepository.save(boardingTrip);
        flushAndClear();
        assign(dispatcherToken, new AssignmentCreateRequest(trip.getId(), userId("driver@test.com"), null))
                .andExpect(status().isCreated());
    }

    @Test
    void assign_withDispatcherIdOfAnotherUser_shouldReturn400() throws Exception {
        String dispatcherToken = staff("disp@test.com", User.Role.DISPATCHER);
        staff("disp2@test.com", User.Role.DISPATCHER);
        staff("driver@test.com", User.Role.DRIVER);

        assign(dispatcherToken, new AssignmentCreateRequest(trip.getId(), userId("driver@test.com"), userId("disp2@test.com")))
                .andExpect(status().isBadRequest());
    }

    // ---------- Disponibilidad del conductor por intervalo ----------

    @Test
    void assign_driverBusyWithOvernightTripOfPreviousDay_shouldReturn409() throws Exception {
        String dispatcherToken = staff("disp@test.com", User.Role.DISPATCHER);
        staff("driver@test.com", User.Role.DRIVER);
        LocalDate day = trip.getTripDate();
        // Viaje nocturno del día anterior que llega a la 01:00 del día del viaje
        Trip overnight = tripRepository.save(Trip.builder().route(route).bus(busRepository.save(newBus(40)))
                .tripDate(day.minusDays(1)).departureTime(day.minusDays(1).atTime(21, 0))
                .arrivalEta(day.atTime(1, 0)).status(Trip.TripStatus.SCHEDULED).build());
        Trip early = tripRepository.save(Trip.builder().route(route).bus(busRepository.save(newBus(40)))
                .tripDate(day).departureTime(day.atTime(0, 30)).arrivalEta(day.atTime(3, 0))
                .status(Trip.TripStatus.SCHEDULED).build());
        flushAndClear();

        assign(dispatcherToken, new AssignmentCreateRequest(overnight.getId(), userId("driver@test.com"), null))
                .andExpect(status().isCreated());
        assign(dispatcherToken, new AssignmentCreateRequest(early.getId(), userId("driver@test.com"), null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("horario")));
        // El viaje de las 12:00 no se cruza
        assign(dispatcherToken, new AssignmentCreateRequest(trip.getId(), userId("driver@test.com"), null))
                .andExpect(status().isCreated());
    }

    // ---------- Cambio de bus en la asignación ----------

    @Test
    void assignAndUpdate_withBusId_shouldValidateAvailabilityAndCapacity() throws Exception {
        String dispatcherToken = staff("disp@test.com", User.Role.DISPATCHER);
        staff("driver@test.com", User.Role.DRIVER);
        String paxToken = registerAndLogin("pax@test.com");
        ticketId(purchase(paxToken, userId("pax@test.com"), 30));

        Bus small = busRepository.save(newBus(20));
        Bus busy = busRepository.save(newBus(45));
        Bus inactive = newBus(45);
        inactive.setStatus(Bus.BusStatus.MAINTENANCE);
        inactive = busRepository.save(inactive);
        Bus free = busRepository.save(newBus(45));
        // El bus "busy" tiene otro viaje que se cruza con este (13:00-15:00)
        tripRepository.save(Trip.builder().route(route).bus(busy).tripDate(trip.getTripDate())
                .departureTime(trip.getTripDate().atTime(13, 0)).arrivalEta(trip.getTripDate().atTime(15, 0))
                .status(Trip.TripStatus.SCHEDULED).build());
        flushAndClear();

        // Silla 30 vendida: el bus de 20 sillas no sirve
        assign(dispatcherToken, new AssignmentCreateRequest(trip.getId(), userId("driver@test.com"), null, small.getId()))
                .andExpect(status().isConflict());
        assign(dispatcherToken, new AssignmentCreateRequest(trip.getId(), userId("driver@test.com"), null, busy.getId()))
                .andExpect(status().isConflict());
        assign(dispatcherToken, new AssignmentCreateRequest(trip.getId(), userId("driver@test.com"), null, inactive.getId()))
                .andExpect(status().isBadRequest());
        assign(dispatcherToken, new AssignmentCreateRequest(trip.getId(), userId("driver@test.com"), null, free.getId()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.busPlate").value(free.getPlate()));

        // Volver al bus original con PUT
        mvc.perform(put("/api/v1/trips/{id}/assignment", trip.getId()).header("Authorization", bearer(dispatcherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new AssignmentUpdateRequest(null, null, null, null, bus.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.busPlate").value(bus.getPlate()));
        flushAndClear();
        assertThat(tripRepository.findById(trip.getId()).orElseThrow().getBus().getId()).isEqualTo(bus.getId());
    }

    // ---------- Checklist con vigencia (SOAT y revisión) ----------

    @Test
    void depart_withExpiredSoat_shouldReturn400WithDetail() throws Exception {
        String driverToken = staff("driver@test.com", User.Role.DRIVER);
        String dispatcherToken = staff("disp@test.com", User.Role.DISPATCHER);
        assign(dispatcherToken, new AssignmentCreateRequest(trip.getId(), userId("driver@test.com"), null))
                .andExpect(status().isCreated());
        approveChecklist(dispatcherToken).andExpect(status().isOk());
        boarding(dispatcherToken, "open").andExpect(status().isOk());

        // El SOAT del bus vence el día antes del viaje (se registró después de aprobar el checklist)
        LocalDate expiresAt = trip.getTripDate().minusDays(1);
        Bus expired = busRepository.findById(bus.getId()).orElseThrow();
        expired.setSoatExpiresAt(expiresAt);
        expired.setTechnicalReviewExpiresAt(trip.getTripDate().plusYears(1));
        busRepository.save(expired);
        flushAndClear();

        mvc.perform(get("/api/v1/trips/{id}/assignment", trip.getId()).header("Authorization", bearer(dispatcherToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.soatExpiresAt").value(expiresAt.toString()))
                .andExpect(jsonPath("$.soatValidOnTripDate").value(false))
                .andExpect(jsonPath("$.reviewValidOnTripDate").value(true));
        mvc.perform(post("/api/v1/trips/{id}/depart", trip.getId()).header("Authorization", bearer(driverToken)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("SOAT vencido el " + expiresAt)));
        // Tampoco se puede volver a aprobar el checklist con el SOAT vencido
        approveChecklist(dispatcherToken)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("SOAT vencido")));
        flushAndClear();
        assertThat(tripRepository.findById(trip.getId()).orElseThrow().getStatus()).isEqualTo(Trip.TripStatus.BOARDING);
    }

    // ---------- Políticas de overbooking: edición ----------

    @Test
    void overbookingPolicy_put_shouldValidateRangeAndOverlapExcludingItself() throws Exception {
        String dispatcherToken = staff("disp@test.com", User.Role.DISPATCHER);
        Long morning = createPolicy(dispatcherToken, 6, 10);
        createPolicy(dispatcherToken, 12, 14);

        // Ampliar la propia franja no choca consigo misma
        updatePolicy(dispatcherToken, morning, 6, 12, "0.08")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.endHour").value(12))
                .andExpect(jsonPath("$.maxPercentage").value(0.08));
        updatePolicy(dispatcherToken, morning, 6, 13, "0.08").andExpect(status().isConflict());
        updatePolicy(dispatcherToken, morning, 10, 10, "0.08").andExpect(status().isBadRequest());
        updatePolicy(dispatcherToken, 999999L, 1, 2, "0.08").andExpect(status().isNotFound());
        flushAndClear();
        assertThat(policyRepository.findById(morning).orElseThrow().getEndHour()).isEqualTo(12);
    }

    // ---------- Conflictos de sincronización offline ----------

    @Test
    void syncConflicts_shouldListOpenByDefault_andResolve() throws Exception {
        String driverToken = staff("driver@test.com", User.Role.DRIVER);
        String dispatcherToken = staff("disp@test.com", User.Role.DISPATCHER);
        String clerkToken = staff("clerk@test.com", User.Role.CLERK);

        // Un abordaje offline con un QR inexistente queda como conflicto del conductor
        mvc.perform(post("/api/v1/sync/boardings").header("Authorization", bearer(driverToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new BoardingSyncRequest("phone-1",
                                List.of(new OfflineBoarding("QR-INEXISTENTE", LocalDateTime.now().minusMinutes(5)))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conflicts").value(1));
        String body = mvc.perform(get("/api/v1/sync/conflicts").header("Authorization", bearer(driverToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].resolvedAt").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        long conflictId = om.readTree(body).get(0).get("id").asLong();

        // El conductor no resuelve; la taquilla no resuelve conflictos ajenos; el despachador sí
        mvc.perform(patch("/api/v1/sync/conflicts/{id}/resolve", conflictId).header("Authorization", bearer(driverToken)))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/v1/sync/conflicts/{id}/resolve", conflictId).header("Authorization", bearer(clerkToken)))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/v1/sync/conflicts/{id}/resolve", conflictId).header("Authorization", bearer(dispatcherToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolvedAt").isNotEmpty())
                .andExpect(jsonPath("$.resolvedById").value(userId("disp@test.com")));
        mvc.perform(patch("/api/v1/sync/conflicts/{id}/resolve", conflictId).header("Authorization", bearer(dispatcherToken)))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(patch("/api/v1/sync/conflicts/{id}/resolve", 999999L).header("Authorization", bearer(dispatcherToken)))
                .andExpect(status().isNotFound());

        // Ya no aparece entre los abiertos, sí entre los resueltos
        mvc.perform(get("/api/v1/sync/conflicts").header("Authorization", bearer(driverToken)))
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/v1/sync/conflicts").param("resolved", "true").param("deviceId", "phone-1")
                        .header("Authorization", bearer(driverToken)))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(conflictId));
        flushAndClear();
        assertThat(syncConflictRepository.findById(conflictId).orElseThrow().getResolvedAt()).isNotNull();
    }

    // ---------- Llegada próxima de viajes retrasados ----------

    @Test
    void findDepartedTripsArrivingBy_shouldIncludeDelayedTripsAndSkipNotifiedOnes() {
        Trip delayed = tripRepository.save(Trip.builder().route(route).bus(bus).tripDate(LocalDate.now())
                .departureTime(LocalDateTime.now().minusHours(3)).arrivalEta(LocalDateTime.now().minusMinutes(30))
                .status(Trip.TripStatus.DEPARTED).build());
        Trip notified = tripRepository.save(Trip.builder().route(route).bus(bus).tripDate(LocalDate.now())
                .departureTime(LocalDateTime.now().minusHours(3)).arrivalEta(LocalDateTime.now().minusMinutes(30))
                .status(Trip.TripStatus.DEPARTED).arrivalNotified(true).build());
        Trip far = tripRepository.save(Trip.builder().route(route).bus(bus).tripDate(LocalDate.now())
                .departureTime(LocalDateTime.now().minusHours(1)).arrivalEta(LocalDateTime.now().plusHours(2))
                .status(Trip.TripStatus.DEPARTED).build());
        flushAndClear();

        List<Long> ids = tripRepository.findDepartedTripsArrivingBy(LocalDateTime.now().plusMinutes(15)).stream()
                .map(Trip::getId).toList();

        assertThat(ids).contains(delayed.getId()).doesNotContain(notified.getId(), far.getId());
    }

    // ---------- Tareas programadas ----------

    @Test
    void scheduling_enabledByDefault_shouldRegisterScheduledTasks() {
        List<String> tasks = applicationContext.getBeanProvider(ScheduledTaskHolder.class).stream()
                .flatMap(holder -> holder.getScheduledTasks().stream())
                .map(ScheduledTask::toString)
                .toList();

        assertThat(tasks).anyMatch(t -> t.contains("NotificationScheduler.notifyUpcomingArrivals"));
        assertThat(tasks).anyMatch(t -> t.contains("processNoShows"));
    }

    // ---------- Helpers ----------

    private Bus newBus(int capacity) {
        return Bus.builder()
                .plate("D" + (System.nanoTime() % 10000000))
                .capacity(capacity)
                .amenities(new HashMap<>())
                .status(Bus.BusStatus.ACTIVE)
                .build();
    }

    private ResultActions assign(String token, AssignmentCreateRequest request) throws Exception {
        return mvc.perform(post("/api/v1/trips/{id}/assign", request.tripId())
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(request)));
    }

    private ResultActions approveChecklist(String token) throws Exception {
        return mvc.perform(put("/api/v1/trips/{id}/assignment", trip.getId())
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(new AssignmentUpdateRequest(null, true, true, true))));
    }

    private ResultActions boarding(String token, String action) throws Exception {
        return mvc.perform(post("/api/v1/trips/{id}/boarding/{action}", trip.getId(), action)
                .header("Authorization", bearer(token)));
    }

    private ResultActions board(String token, String qr) throws Exception {
        return mvc.perform(post("/api/v1/tickets/qr/{qr}/board", qr).header("Authorization", bearer(token)));
    }

    private Long createPolicy(String token, int start, int end) throws Exception {
        String body = mvc.perform(post("/api/v1/routes/{id}/overbooking-policies", route.getId())
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new OverbookingPolicyCreateRequest(start, end, new BigDecimal("0.05")))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return om.readTree(body).get("id").asLong();
    }

    private ResultActions updatePolicy(String token, Long id, int start, int end, String percentage) throws Exception {
        return mvc.perform(put("/api/v1/overbooking-policies/{id}", id)
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(new OverbookingPolicyCreateRequest(start, end, new BigDecimal(percentage)))));
    }

    private ResultActions purchase(String token, Long passengerId, int seat) throws Exception {
        TicketCreateRequest request = new TicketCreateRequest(
                trip.getId(), passengerId, seat,
                stopA.getId(), stopA.getName(), stopA.getOrder(),
                stopC.getId(), stopC.getName(), stopC.getOrder(),
                new BigDecimal("50000"), Ticket.PaymentMethod.CASH, null, null);
        return mvc.perform(post("/api/v1/trips/{tripId}/tickets", trip.getId())
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(request)));
    }

    private Long ticketId(ResultActions purchase) throws Exception {
        JsonNode node = om.readTree(purchase.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return node.get("id").asLong();
    }

    private String qr(Long ticketId) {
        return ticketRepository.findById(ticketId).orElseThrow().getQrCode();
    }

    private String staff(String email, User.Role role) throws Exception {
        createUser(new RegisterRequest("Staff " + role, email, "3009999999", "secreto1", role));
        return login(email, "secreto1");
    }

    private String registerAndLogin(String email) throws Exception {
        mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(
                                new RegisterRequest("Pasajero", email, "3001234567", "secreto1", User.Role.PASSENGER))))
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

    private Long userId(String email) {
        return userRepository.findByEmail(email).orElseThrow().getId();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
