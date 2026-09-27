package com.web.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.dto.admin.ConfigUpdateRequest;
import com.web.dto.auth.Login.LoginRequest;
import com.web.dto.auth.Login.RegisterRequest;
import com.web.dto.baggage.BaggageCreateRequest;
import com.web.dto.dispatch.Assignment.AssignmentCreateRequest;
import com.web.dto.dispatch.Assignment.AssignmentUpdateRequest;
import com.web.dto.parcel.ParcelCreateRequest;
import com.web.dto.parcel.ParcelStatusUpdateRequest;
import com.web.dto.payment.CashCloseRequest;
import com.web.dto.ticket.TicketCreateRequest;
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
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Requisitos del documento del proyecto y hallazgos de la revisión, probados de extremo a extremo (HTTP + JWT + BD)
class ProjectRequirementsIntegrationTest extends BaseIntegrationTest {

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
    private ParcelRepository parcelRepository;

    @Autowired
    private FareRuleRepository fareRuleRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    private Route route;
    private Stop stopA;
    private Stop stopB;
    private Stop stopC;
    private Bus bus;
    private Trip trip;

    @BeforeEach
    void setUp() {
        route = routeRepository.save(Route.builder()
                .code("REQ-" + System.nanoTime())
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

        bus = busRepository.save(Bus.builder()
                .plate("REQ" + (System.nanoTime() % 100000))
                .capacity(40)
                .amenities(new HashMap<>())
                .status(Bus.BusStatus.ACTIVE)
                .build());

        LocalDate date = LocalDate.now().plusDays(3);
        trip = tripRepository.save(Trip.builder()
                .route(route)
                .bus(bus)
                .tripDate(date)
                .departureTime(date.atTime(12, 0))
                .arrivalEta(date.atTime(14, 0))
                .status(Trip.TripStatus.SCHEDULED)
                .build());

        entityManager.flush();
        entityManager.clear();
    }

    // ---------- Despacho: autenticación del DRIVER en la salida, no-show determinista, llegada ----------

    @Test
    void depart_shouldRequireAssignedDriver_markNoShowsAndRegisterTimes() throws Exception {
        String paxToken = registerAndLogin("pax@test.com");
        Long boarded = ticketId(purchase(paxToken, userId("pax@test.com"), 1, stopA, stopC, null, null));
        Long missing = ticketId(purchase(paxToken, userId("pax@test.com"), 2, stopA, stopC, null, null));
        Long intermediate = ticketId(purchase(paxToken, userId("pax@test.com"), 3, stopB, stopC, null, null));

        String driverToken = staff("driver@test.com", User.Role.DRIVER);
        String otherDriverToken = staff("driver2@test.com", User.Role.DRIVER);
        String dispatcherToken = staff("disp@test.com", User.Role.DISPATCHER);
        assignAndApproveChecklist(dispatcherToken, "driver@test.com", "disp@test.com");

        mvc.perform(post("/api/v1/trips/{id}/boarding/open", trip.getId()).header("Authorization", bearer(dispatcherToken)))
                .andExpect(status().isOk());
        board(driverToken, qr(boarded)).andExpect(status().isOk());

        // Otro conductor no puede dar salida a este viaje
        mvc.perform(post("/api/v1/trips/{id}/depart", trip.getId()).header("Authorization", bearer(otherDriverToken)))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/v1/trips/{id}/depart", trip.getId()).header("Authorization", bearer(driverToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DEPARTED"));

        flushAndClear();
        Ticket noShow = ticketRepository.findById(missing).orElseThrow();
        assertThat(noShow.getStatus()).isEqualTo(Ticket.TicketStatus.NO_SHOW);
        assertThat(noShow.getNoShowFee()).isNotNull().isPositive();
        assertThat(ticketRepository.findById(boarded).orElseThrow().getStatus()).isEqualTo(Ticket.TicketStatus.SOLD);
        // Quien sube en una parada intermedia no se marca al salir de la primera
        assertThat(ticketRepository.findById(intermediate).orElseThrow().getStatus()).isEqualTo(Ticket.TicketStatus.SOLD);
        assertThat(tripRepository.findById(trip.getId()).orElseThrow().getDepartedAt()).isNotNull();

        // Llegada: solo el conductor asignado
        mvc.perform(post("/api/v1/trips/{id}/arrive", trip.getId()).header("Authorization", bearer(otherDriverToken)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/trips/{id}/arrive", trip.getId()).header("Authorization", bearer(driverToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARRIVED"));
        flushAndClear();
        assertThat(tripRepository.findById(trip.getId()).orElseThrow().getArrivedAt()).isNotNull();
    }

    // Cerrar abordaje marca no-show; si el pasajero llega antes de la salida y su silla sigue libre, se restituye
    @Test
    void closeBoarding_shouldMarkNoShow_andLatePassengerCanStillBoardIfSeatFree() throws Exception {
        String paxToken = registerAndLogin("late@test.com");
        Long late = ticketId(purchase(paxToken, userId("late@test.com"), 5, stopA, stopC, null, null));
        Long resold = ticketId(purchase(paxToken, userId("late@test.com"), 6, stopA, stopC, null, null));

        String driverToken = staff("driver@test.com", User.Role.DRIVER);
        String dispatcherToken = staff("disp@test.com", User.Role.DISPATCHER);
        assignAndApproveChecklist(dispatcherToken, "driver@test.com", "disp@test.com");

        mvc.perform(post("/api/v1/trips/{id}/boarding/open", trip.getId()).header("Authorization", bearer(dispatcherToken)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/trips/{id}/boarding/close", trip.getId()).header("Authorization", bearer(dispatcherToken)))
                .andExpect(status().isOk());
        flushAndClear();
        assertThat(ticketRepository.findById(late).orElseThrow().getStatus()).isEqualTo(Ticket.TicketStatus.NO_SHOW);

        // La silla 6 vuelve a venta rápida y otro pasajero la compra
        String otherToken = registerAndLogin("other@test.com");
        purchase(otherToken, userId("other@test.com"), 6, stopA, stopC, null, null).andExpect(status().isCreated());

        // El pasajero de la silla 5 llega tarde: se restituye y aborda
        board(driverToken, qr(late)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SOLD"))
                .andExpect(jsonPath("$.boardedAt").isNotEmpty());
        // El de la silla 6 ya perdió su silla
        board(driverToken, qr(resold)).andExpect(status().isConflict());
    }

    // ---------- Encomiendas: OTP solo para la taquilla, máquina de estados del documento ----------

    @Test
    void parcel_otpOnlyOnCreation_failedIsFinal_andTrackingHidesPersonalData() throws Exception {
        String clerkToken = staff("clerk@test.com", User.Role.CLERK);
        String driverToken = staff("driver@test.com", User.Role.DRIVER);

        String body = mvc.perform(post("/api/v1/parcels")
                        .header("Authorization", bearer(clerkToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new ParcelCreateRequest(
                                trip.getId(), "Remitente", "3001", "Destinatario", "3002",
                                stopA.getId(), null, stopC.getId(), null,
                                new BigDecimal("15000"), new BigDecimal("2.5"), "Caja"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.deliveryOtp").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String code = om.readTree(body).get("code").asText();

        // El conductor la pone en tránsito pero no ve el OTP
        parcelStatus(driverToken, code, Parcel.ParcelStatus.IN_TRANSIT)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveryOtp").doesNotExist());

        // OTP incorrecto: FAILED + incidente, y la respuesta tampoco trae el OTP
        mvc.perform(post("/api/v1/parcels/{code}/deliver", code)
                        .header("Authorization", bearer(driverToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new ParcelStatusUpdateRequest(
                                code, Parcel.ParcelStatus.DELIVERED, "000000", "https://foto"))))
                .andExpect(status().isBadRequest());

        // FAILED es final: no se puede volver a IN_TRANSIT para reintentar el OTP
        parcelStatus(driverToken, code, Parcel.ParcelStatus.IN_TRANSIT).andExpect(status().isUnprocessableEntity());

        // El rastreo público no expone OTP ni datos personales
        mvc.perform(get("/api/v1/parcels/{code}/track", code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.deliveryOtp").doesNotExist())
                .andExpect(jsonPath("$.senderPhone").doesNotExist())
                .andExpect(jsonPath("$.receiverPhone").doesNotExist());
    }

    // ---------- Cierre de caja: ventas en efectivo + exceso de equipaje - reembolsos del día ----------

    @Test
    void cashClose_shouldIncludeBaggageFeesAndSubtractRefunds() throws Exception {
        String clerkToken = staff("clerk@test.com", User.Role.CLERK);
        // Base del día (puede haber ventas en efectivo de los datos semilla)
        BigDecimal baseline = expectedCash(clerkToken);

        String paxToken = registerAndLogin("cash@test.com");
        Long pax = userId("cash@test.com");
        JsonNode withBaggage = json(purchase(clerkToken, pax, 1, stopA, stopC, null,
                new BaggageCreateRequest(new BigDecimal("30.0"), null)));
        JsonNode cancelled = json(purchase(clerkToken, pax, 2, stopA, stopC, null, null));

        String cancelBody = mvc.perform(post("/api/v1/tickets/{id}/cancel", cancelled.get("id").asLong())
                        .header("Authorization", bearer(paxToken)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        BigDecimal refund = new BigDecimal(om.readTree(cancelBody).get("refundAmount").asText());

        BigDecimal expected = baseline
                .add(new BigDecimal(withBaggage.get("price").asText()))
                .add(new BigDecimal(withBaggage.get("baggage").get("excessFee").asText()))
                .add(new BigDecimal(cancelled.get("price").asText()))
                .subtract(refund);

        assertThat(new BigDecimal(withBaggage.get("baggage").get("excessFee").asText())).isPositive();
        assertThat(expectedCash(clerkToken)).isEqualByComparingTo(expected);
    }

    // ---------- Métricas y conteo de equipaje ----------

    @Test
    void metricsAndBaggageSummary_shouldReflectSales() throws Exception {
        String clerkToken = staff("clerk@test.com", User.Role.CLERK);
        String adminToken = staff("admin@test.com", User.Role.ADMIN);
        String dispatcherToken = staff("disp@test.com", User.Role.DISPATCHER);
        String paxToken = registerAndLogin("metrics@test.com");
        Long pax = userId("metrics@test.com");

        purchase(clerkToken, pax, 1, stopA, stopC, null, new BaggageCreateRequest(new BigDecimal("30.0"), null))
                .andExpect(status().isCreated());
        purchase(paxToken, pax, 2, stopA, stopC, null, new BaggageCreateRequest(new BigDecimal("10.0"), null))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/v1/trips/{id}/baggage", trip.getId()).header("Authorization", bearer(dispatcherToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalPieces").value(2))
                .andExpect(jsonPath("$.totalWeightKg").value(40.0));
        mvc.perform(get("/api/v1/trips/{id}/baggage", trip.getId()).header("Authorization", bearer(paxToken)))
                .andExpect(status().isForbidden());

        String date = trip.getTripDate().toString();
        mvc.perform(get("/api/v1/admin/metrics").param("startDate", date).param("endDate", date)
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.occupancy.totalSeatsSold").isNumber())
                .andExpect(jsonPath("$.revenue.revenueByChannel.BOX_OFFICE").isNumber())
                .andExpect(jsonPath("$.revenue.revenueByChannel.APP").isNumber())
                .andExpect(jsonPath("$.revenue.baggageRevenue").isNumber());
        mvc.perform(get("/api/v1/admin/metrics").param("startDate", date).param("endDate", "2000-01-01")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/admin/metrics").param("startDate", date).param("endDate", date)
                        .header("Authorization", bearer(paxToken)))
                .andExpect(status().isForbidden());

        flushAndClear();
        assertThat(ticketRepository.findByTripId(trip.getId()))
                .extracting(Ticket::getChannel)
                .containsExactlyInAnyOrder(Ticket.SalesChannel.BOX_OFFICE, Ticket.SalesChannel.APP);
    }

    // ---------- Tarifas por tramo (FareRule) y descuentos ----------

    @Test
    void fareRule_withDynamicPricingOff_andOwnDiscounts_shouldBeApplied() throws Exception {
        fareRuleRepository.saveAndFlush(FareRule.builder()
                .route(routeRepository.findById(route.getId()).orElseThrow())
                .fromStop(stopRepository.findById(stopA.getId()).orElseThrow())
                .toStop(stopRepository.findById(stopB.getId()).orElseThrow())
                .basePrice(new BigDecimal("30000"))
                .discounts(Map.of("STUDENT", 50))
                .dynamicPricingEnabled(false)
                .build());

        String token = registerAndLogin("fare@test.com");
        Long pax = userId("fare@test.com");

        purchase(token, pax, 1, stopA, stopB, "STUDENT", null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.price").value(15000.0));
        purchase(token, pax, 2, stopA, stopB, "ADULT", null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.price").value(30000.0));
        // Tipo de pasajero desconocido
        purchase(token, pax, 3, stopA, stopB, "VIP", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("Tipo de pasajero no válido")));
    }

    @Test
    void config_withInvalidValues_shouldReturn400() throws Exception {
        String adminToken = staff("admin@test.com", User.Role.ADMIN);

        updateConfig(adminToken, new ConfigUpdateRequest(null, null, null, Map.of("STUDENT", 150), null, null,
                null, null, null, null, null, null, null, null, null, null, null))
                .andExpect(status().isBadRequest());
        updateConfig(adminToken, new ConfigUpdateRequest(null, null, null, Map.of("VIP", 10), null, null,
                null, null, null, null, null, null, null, null, null, null, null))
                .andExpect(status().isBadRequest());
        updateConfig(adminToken, new ConfigUpdateRequest(0, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null))
                .andExpect(status().isBadRequest());
        updateConfig(adminToken, new ConfigUpdateRequest(null, null, null, null, null, null,
                null, -0.5, null, null, null, null, null, null, null, null, null))
                .andExpect(status().isBadRequest());
    }

    // ---------- Creación de viajes y ventas ----------

    @Test
    void createTrip_shouldValidateBusAvailabilityRouteAndDates() throws Exception {
        String adminToken = staff("admin@test.com", User.Role.ADMIN);
        LocalDate date = trip.getTripDate();

        // El bus ya tiene un viaje ese día
        createTrip(adminToken, new TripCreateRequest(route.getId(), bus.getId(), date,
                date.atTime(18, 0), date.atTime(20, 0)))
                .andExpect(status().isConflict());
        // Llegada anterior a la salida
        createTrip(adminToken, new TripCreateRequest(route.getId(), bus.getId(), date.plusDays(1),
                date.plusDays(1).atTime(18, 0), date.plusDays(1).atTime(17, 0)))
                .andExpect(status().isBadRequest());
        // Fecha del viaje distinta a la de salida
        createTrip(adminToken, new TripCreateRequest(route.getId(), bus.getId(), date.plusDays(1),
                date.plusDays(2).atTime(8, 0), date.plusDays(2).atTime(10, 0)))
                .andExpect(status().isBadRequest());

        // Ruta inactiva (borrado lógico)
        Route inactive = routeRepository.findById(route.getId()).orElseThrow();
        inactive.setIsActive(false);
        routeRepository.saveAndFlush(inactive);
        createTrip(adminToken, new TripCreateRequest(route.getId(), bus.getId(), date.plusDays(1),
                date.plusDays(1).atTime(8, 0), date.plusDays(1).atTime(10, 0)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("inactiva")));
    }

    @Test
    void purchase_afterScheduledDeparture_shouldReturn400() throws Exception {
        Trip late = tripRepository.findById(trip.getId()).orElseThrow();
        late.setDepartureTime(LocalDateTime.now().minusMinutes(1));
        tripRepository.saveAndFlush(late);

        String token = registerAndLogin("late-buyer@test.com");
        purchase(token, userId("late-buyer@test.com"), 1, stopA, stopC, null, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("ya salió")));
    }

    // ---------- Errores HTTP estándar ----------

    @Test
    void unknownRouteAndUnsupportedMethod_shouldReturn404And405() throws Exception {
        String token = registerAndLogin("errors@test.com");

        mvc.perform(get("/api/v1/does-not-exist").header("Authorization", bearer(token)))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isMethodNotAllowed());
    }

    // ---------- Helpers ----------

    private ResultActions purchase(String token, Long passengerId, int seat, Stop from, Stop to,
                                   String passengerType, BaggageCreateRequest baggage) throws Exception {
        TicketCreateRequest request = new TicketCreateRequest(
                trip.getId(), passengerId, seat,
                from.getId(), from.getName(), from.getOrder(),
                to.getId(), to.getName(), to.getOrder(),
                new BigDecimal("50000"), Ticket.PaymentMethod.CASH, baggage, passengerType);

        return mvc.perform(post("/api/v1/trips/{tripId}/tickets", trip.getId())
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(request)));
    }

    private ResultActions board(String token, String qr) throws Exception {
        return mvc.perform(post("/api/v1/tickets/qr/{qr}/board", qr).header("Authorization", bearer(token)));
    }

    private ResultActions parcelStatus(String token, String code, Parcel.ParcelStatus status) throws Exception {
        return mvc.perform(put("/api/v1/parcels/{code}/status", code)
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(new ParcelStatusUpdateRequest(code, status, null, null))));
    }

    private ResultActions updateConfig(String token, ConfigUpdateRequest request) throws Exception {
        return mvc.perform(put("/api/v1/admin/config")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(request)));
    }

    private ResultActions createTrip(String token, TripCreateRequest request) throws Exception {
        return mvc.perform(post("/api/v1/trips")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(request)));
    }

    private void assignAndApproveChecklist(String dispatcherToken, String driverEmail, String dispatcherEmail) throws Exception {
        mvc.perform(post("/api/v1/trips/{tripId}/assign", trip.getId())
                        .header("Authorization", bearer(dispatcherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new AssignmentCreateRequest(
                                trip.getId(), userId(driverEmail), userId(dispatcherEmail)))))
                .andExpect(status().isCreated());
        mvc.perform(put("/api/v1/trips/{tripId}/assignment", trip.getId())
                        .header("Authorization", bearer(dispatcherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new AssignmentUpdateRequest(null, true, true, true))))
                .andExpect(status().isOk());
    }

    private BigDecimal expectedCash(String clerkToken) throws Exception {
        String body = mvc.perform(post("/api/v1/cash/close")
                        .header("Authorization", bearer(clerkToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new CashCloseRequest(
                                userId("clerk@test.com"), LocalDate.now(), null, BigDecimal.ZERO, null))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return new BigDecimal(om.readTree(body).get("expectedAmount").asText());
    }

    private String qr(Long ticketId) {
        return ticketRepository.findById(ticketId).orElseThrow().getQrCode();
    }

    private Long ticketId(ResultActions purchase) throws Exception {
        return json(purchase).get("id").asLong();
    }

    private JsonNode json(ResultActions purchase) throws Exception {
        return om.readTree(purchase.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
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
