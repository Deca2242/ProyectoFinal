package com.web.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.dto.auth.Login.LoginRequest;
import com.web.dto.auth.Login.RegisterRequest;
import com.web.dto.baggage.BaggageCreateRequest;
import com.web.dto.parcel.ParcelCreateRequest;
import com.web.dto.parcel.ParcelReopenRequest;
import com.web.dto.parcel.ParcelStatusUpdateRequest;
import com.web.dto.ticket.TicketCreateRequest;
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
import java.util.HashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Encomiendas (OTP con intentos, incidentes, reapertura, conductor) y equipaje (fee, maletero, máximo) de extremo a extremo
class ParcelBaggageIntegrationTest extends BaseIntegrationTest {

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
    private ParcelRepository parcelRepository;

    @Autowired
    private AssignmentRepository assignmentRepository;

    @Autowired
    private NotificationRepository notificationRepository;

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
                .code("PB-" + System.nanoTime())
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
                .plate("PB" + (System.nanoTime() % 100000))
                .capacity(40)
                .amenities(new HashMap<>())
                .status(Bus.BusStatus.ACTIVE)
                .build());

        trip = newTrip(3);

        entityManager.flush();
        entityManager.clear();
    }

    // ---------- Entrega feliz ----------

    @Test
    void parcel_happyDelivery_createInTransitAndDeliverWithOtp() throws Exception {
        String clerk = staff("clerk@test.com", User.Role.CLERK);
        String driver = staff("driver@test.com", User.Role.DRIVER);
        String dispatcher = staff("disp@test.com", User.Role.DISPATCHER);
        // El destinatario es un usuario registrado: queda registro de su notificación (sin el OTP)
        createUser(new RegisterRequest("Destinatario", "dest@test.com", "3005550001", "secreto1", User.Role.PASSENGER));
        assignDriver("driver@test.com");

        JsonNode created = createParcel(clerk, "3005550001");
        String code = created.get("code").asText();
        String otp = created.get("deliveryOtp").asText();
        Long parcelId = created.get("id").asLong();
        assertThat(otp).matches("\\d{6}");
        assertThat(created.get("description").asText()).isEqualTo("Caja con documentos");

        // En la BD solo queda el hash del OTP, y la descripción se persiste
        flushAndClear();
        Parcel stored = parcelRepository.findById(parcelId).orElseThrow();
        assertThat(stored.getDeliveryOtp()).hasSize(64).isNotEqualTo(otp);
        assertThat(stored.getDescription()).isEqualTo("Caja con documentos");
        List<Notification> notifications = notificationRepository.findByTripIdOrderByCreatedAtDescIdDesc(trip.getId());
        assertThat(notifications).singleElement().satisfies(n -> {
            assertThat(n.getType()).isEqualTo(Notification.NotificationType.PARCEL_CREATED);
            assertThat(n.getRecipient()).isEqualTo("3005550001");
            assertThat(n.getMessage()).contains(code).doesNotContain(otp);
        });

        // Con el viaje aún programado no se puede cargar al bus
        parcelStatus(driver, code, Parcel.ParcelStatus.IN_TRANSIT, null, null)
                .andExpect(status().isUnprocessableEntity());

        mvc.perform(post("/api/v1/trips/{id}/boarding/open", trip.getId()).header("Authorization", bearer(dispatcher)))
                .andExpect(status().isOk());
        parcelStatus(driver, code, Parcel.ParcelStatus.IN_TRANSIT, null, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_TRANSIT"))
                .andExpect(jsonPath("$.deliveryOtp").doesNotExist());

        // DELIVERED por /status sin OTP → 400; por /deliver con OTP y foto → entregada
        parcelStatus(driver, code, Parcel.ParcelStatus.DELIVERED, null, null)
                .andExpect(status().isBadRequest());
        deliver(driver, code, otp, "https://fotos/entrega.jpg")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"))
                .andExpect(jsonPath("$.proofPhotoUrl").value("https://fotos/entrega.jpg"))
                .andExpect(jsonPath("$.deliveredAt").isNotEmpty());

        // Entregada: no hay incidente y el rastreo público muestra el estado sin datos personales
        mvc.perform(get("/api/v1/incidents").header("Authorization", bearer(dispatcher))
                        .param("entityType", "PARCEL").param("entityId", parcelId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get("/api/v1/parcels/{code}/track", code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"))
                .andExpect(jsonPath("$.receiverPhone").doesNotExist())
                .andExpect(jsonPath("$.senderPhone").doesNotExist());
    }

    // ---------- OTP inválido: intentos, FAILED + incidente, reapertura ----------

    @Test
    void parcel_invalidOtp_countsAttempts_failsOnThird_andDispatcherReopens() throws Exception {
        String clerk = staff("clerk@test.com", User.Role.CLERK);
        String driver = staff("driver@test.com", User.Role.DRIVER);
        String dispatcher = staff("disp@test.com", User.Role.DISPATCHER);
        assignDriver("driver@test.com");
        JsonNode created = createParcel(clerk, "3002");
        String code = created.get("code").asText();
        String otp = created.get("deliveryOtp").asText();
        Long parcelId = created.get("id").asLong();
        setTripStatus(trip, Trip.TripStatus.BOARDING);
        parcelStatus(driver, code, Parcel.ParcelStatus.IN_TRANSIT, null, null).andExpect(status().isOk());

        String wrongOtp = otp.equals("000000") ? "111111" : "000000";
        deliver(driver, code, wrongOtp, "https://foto")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("Intentos restantes: 2")));
        deliver(driver, code, wrongOtp, "https://foto")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("Intentos restantes: 1")));
        flushAndClear();
        Parcel afterTwo = parcelRepository.findById(parcelId).orElseThrow();
        assertThat(afterTwo.getStatus()).isEqualTo(Parcel.ParcelStatus.IN_TRANSIT);
        assertThat(afterTwo.getOtpAttempts()).isEqualTo(2);

        // Tercer intento: FAILED + incidente visible en /incidents
        deliver(driver, code, wrongOtp, "https://foto")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("FAILED")));
        flushAndClear();
        assertThat(parcelRepository.findById(parcelId).orElseThrow().getStatus()).isEqualTo(Parcel.ParcelStatus.FAILED);

        String incidents = mvc.perform(get("/api/v1/incidents").header("Authorization", bearer(dispatcher))
                        .param("entityType", "PARCEL").param("entityId", parcelId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].type").value("DELIVERY_FAIL"))
                .andExpect(jsonPath("$[0].status").value("OPEN"))
                .andExpect(jsonPath("$[0].reportedById").value(userId("driver@test.com")))
                .andReturn().getResponse().getContentAsString();
        long incidentId = om.readTree(incidents).get(0).get("id").asLong();
        assertThat(om.readTree(incidents).get(0).get("description").asText()).doesNotContain(otp);

        // El conductor ve sus incidentes con reportedBy=me y el detalle del suyo
        mvc.perform(get("/api/v1/incidents").header("Authorization", bearer(driver)).param("reportedBy", "me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(incidentId));
        mvc.perform(get("/api/v1/incidents/{id}", incidentId).header("Authorization", bearer(driver)))
                .andExpect(status().isOk());

        // FAILED no vuelve a IN_TRANSIT por /status; solo DISPATCHER/ADMIN reabren
        parcelStatus(driver, code, Parcel.ParcelStatus.IN_TRANSIT, null, null)
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/api/v1/parcels/{code}/reopen", code).header("Authorization", bearer(driver)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/parcels/{code}/reopen", code).header("Authorization", bearer(dispatcher)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_TRANSIT"))
                .andExpect(jsonPath("$.otpAttempts").value(0));

        // Tras la reapertura se entrega por el alias POST /status con OTP y foto
        mvc.perform(post("/api/v1/parcels/{code}/status", code)
                        .header("Authorization", bearer(driver))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new ParcelStatusUpdateRequest(
                                null, Parcel.ParcelStatus.DELIVERED, otp, "https://foto/ok"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"));

        // El despachador resuelve el incidente
        mvc.perform(patch("/api/v1/incidents/{id}/resolve", incidentId).header("Authorization", bearer(dispatcher)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"));

        // Sin reportedBy=me un conductor no consulta todos los incidentes
        mvc.perform(get("/api/v1/incidents").header("Authorization", bearer(driver)))
                .andExpect(status().isForbidden());
    }

    // ---------- FAILED manual ----------

    @Test
    void parcel_manualFailed_createsIncidentReportedByClerk() throws Exception {
        String clerk = staff("clerk@test.com", User.Role.CLERK);
        String dispatcher = staff("disp@test.com", User.Role.DISPATCHER);
        JsonNode created = createParcel(clerk, "3002");
        String code = created.get("code").asText();
        setTripStatus(trip, Trip.TripStatus.DEPARTED);
        parcelStatus(clerk, code, Parcel.ParcelStatus.IN_TRANSIT, null, null).andExpect(status().isOk());

        parcelStatus(clerk, code, Parcel.ParcelStatus.FAILED, null, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));

        mvc.perform(get("/api/v1/incidents").header("Authorization", bearer(dispatcher))
                        .param("entityType", "PARCEL").param("entityId", created.get("id").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].reportedById").value(userId("clerk@test.com")));
    }

    // ---------- Cancelación del viaje ----------

    @Test
    void cancelTrip_failsPendingParcelsWithIncident_keepsDelivered_andAllowsReassignment() throws Exception {
        String clerk = staff("clerk@test.com", User.Role.CLERK);
        String admin = staff("admin@test.com", User.Role.ADMIN);
        String dispatcher = staff("disp@test.com", User.Role.DISPATCHER);
        JsonNode pending = createParcel(clerk, "3002");
        JsonNode inTransit = createParcel(clerk, "3003");
        JsonNode delivered = createParcel(clerk, "3004");
        setTripStatus(trip, Trip.TripStatus.BOARDING);
        parcelStatus(clerk, inTransit.get("code").asText(), Parcel.ParcelStatus.IN_TRANSIT, null, null)
                .andExpect(status().isOk());
        parcelStatus(clerk, delivered.get("code").asText(), Parcel.ParcelStatus.IN_TRANSIT, null, null)
                .andExpect(status().isOk());
        deliver(clerk, delivered.get("code").asText(), delivered.get("deliveryOtp").asText(), "https://foto")
                .andExpect(status().isOk());

        mvc.perform(delete("/api/v1/trips/{id}", trip.getId()).header("Authorization", bearer(admin)))
                .andExpect(status().isNoContent());
        flushAndClear();

        assertThat(parcelRepository.findById(pending.get("id").asLong()).orElseThrow().getStatus())
                .isEqualTo(Parcel.ParcelStatus.FAILED);
        assertThat(parcelRepository.findById(inTransit.get("id").asLong()).orElseThrow().getStatus())
                .isEqualTo(Parcel.ParcelStatus.FAILED);
        assertThat(parcelRepository.findById(delivered.get("id").asLong()).orElseThrow().getStatus())
                .isEqualTo(Parcel.ParcelStatus.DELIVERED);
        for (JsonNode failed : List.of(pending, inTransit)) {
            mvc.perform(get("/api/v1/incidents").header("Authorization", bearer(dispatcher))
                            .param("entityType", "PARCEL").param("entityId", failed.get("id").asText()))
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].type").value("DELIVERY_FAIL"));
        }
        mvc.perform(get("/api/v1/incidents").header("Authorization", bearer(dispatcher))
                        .param("entityType", "PARCEL").param("entityId", delivered.get("id").asText()))
                .andExpect(jsonPath("$", hasSize(0)));

        // Reapertura: en el viaje cancelado no; reasignada a otra salida programada vuelve a CREATED
        String pendingCode = pending.get("code").asText();
        mvc.perform(post("/api/v1/parcels/{code}/reopen", pendingCode).header("Authorization", bearer(dispatcher)))
                .andExpect(status().isUnprocessableEntity());
        Trip nextTrip = newTrip(4);
        mvc.perform(post("/api/v1/parcels/{code}/reopen", pendingCode)
                        .header("Authorization", bearer(dispatcher))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new ParcelReopenRequest(nextTrip.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.tripId").value(nextTrip.getId()));
    }

    // ---------- Encomiendas del viaje para el conductor ----------

    @Test
    void driver_listsParcelsOfOwnTrip_andGets403OnAnotherTrip() throws Exception {
        String clerk = staff("clerk@test.com", User.Role.CLERK);
        String driver = staff("driver@test.com", User.Role.DRIVER);
        assignDriver("driver@test.com");
        JsonNode first = createParcel(clerk, "3002");
        createParcel(clerk, "3003");
        setTripStatus(trip, Trip.TripStatus.BOARDING);
        parcelStatus(driver, first.get("code").asText(), Parcel.ParcelStatus.IN_TRANSIT, null, null)
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/trips/{id}/parcels", trip.getId()).header("Authorization", bearer(driver)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].deliveryOtp").doesNotExist());
        mvc.perform(get("/api/v1/trips/{id}/parcels", trip.getId()).header("Authorization", bearer(driver))
                        .param("status", "IN_TRANSIT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].code").value(first.get("code").asText()));

        // Listado general con filtros para la taquilla
        mvc.perform(get("/api/v1/parcels").header("Authorization", bearer(clerk))
                        .param("status", "CREATED").param("from", trip.getTripDate().toString())
                        .param("to", trip.getTripDate().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));

        // Viaje de otro conductor: 403; tampoco puede cargar sus encomiendas
        Trip otherTrip = newTrip(5);
        mvc.perform(get("/api/v1/trips/{id}/parcels", otherTrip.getId()).header("Authorization", bearer(driver)))
                .andExpect(status().isForbidden());
    }

    // ---------- Equipaje ----------

    @Test
    void baggage_registeredAfterPurchase_feeFromConfig_summaryByCompartment_andMaxWeight() throws Exception {
        String clerk = staff("clerk@test.com", User.Role.CLERK);
        String admin = staff("admin@test.com", User.Role.ADMIN);
        String dispatcher = staff("disp@test.com", User.Role.DISPATCHER);
        String pax = registerAndLogin("pax@test.com");
        Long paxId = userId("pax@test.com");

        // El ADMIN cambia el límite y la tarifa por kg: el fee usa los valores vigentes
        mvc.perform(put("/api/v1/admin/config")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baggageWeightLimit\": 10, \"baggagePricePerKg\": 1000}"))
                .andExpect(status().isOk());

        Long ticket1 = json(purchase(pax, paxId, 1, null)).get("id").asLong();
        JsonNode ticket2 = json(purchase(pax, paxId, 2, new BaggageCreateRequest(new BigDecimal("12"), null, "a")));
        Long ticket2Id = ticket2.get("id").asLong();
        assertThat(ticket2.get("baggage").get("compartment").asText()).isEqualTo("A");
        assertThat(new BigDecimal(ticket2.get("baggage").get("excessFee").asText())).isEqualByComparingTo("2000");

        // Registro posterior en taquilla: 30 kg → (30 - 10) * 1000
        mvc.perform(post("/api/v1/tickets/{id}/baggage", ticket1)
                        .header("Authorization", bearer(clerk))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new BaggageCreateRequest(new BigDecimal("30"), null, "b"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ticketId").value(ticket1))
                .andExpect(jsonPath("$.excessFee").value(20000.0))
                .andExpect(jsonPath("$.compartment").value("B"))
                .andExpect(jsonPath("$.tagCode").isNotEmpty());
        // Un segundo equipaje para el mismo ticket → 409; un pasajero no registra equipaje → 403
        mvc.perform(post("/api/v1/tickets/{id}/baggage", ticket1)
                        .header("Authorization", bearer(clerk))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new BaggageCreateRequest(new BigDecimal("5"), null))))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/tickets/{id}/baggage", ticket1)
                        .header("Authorization", bearer(pax))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new BaggageCreateRequest(new BigDecimal("5"), null))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/tickets/{id}/baggage", ticket1).header("Authorization", bearer(pax)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.weightKg").value(30.0));

        // Resumen por maletero
        summary(dispatcher)
                .andExpect(jsonPath("$.totalPieces").value(2))
                .andExpect(jsonPath("$.byCompartment.A.pieces").value(1))
                .andExpect(jsonPath("$.byCompartment.A.totalWeightKg").value(12.0))
                .andExpect(jsonPath("$.byCompartment.B.pieces").value(1))
                .andExpect(jsonPath("$.byCompartment.B.totalWeightKg").value(30.0));

        // Al cancelar el ticket 1 su equipaje deja de contar
        mvc.perform(post("/api/v1/tickets/{id}/cancel", ticket1).header("Authorization", bearer(pax)))
                .andExpect(status().isOk());
        summary(dispatcher)
                .andExpect(jsonPath("$.totalPieces").value(1))
                .andExpect(jsonPath("$.byCompartment.B").doesNotExist());

        // La taquilla retira el equipaje del ticket 2 antes de abordar
        mvc.perform(delete("/api/v1/tickets/{id}/baggage", ticket2Id).header("Authorization", bearer(clerk)))
                .andExpect(status().isNoContent());
        flushAndClear();
        mvc.perform(get("/api/v1/tickets/{id}/baggage", ticket2Id).header("Authorization", bearer(clerk)))
                .andExpect(status().isNotFound());
        summary(dispatcher).andExpect(jsonPath("$.totalPieces").value(0));

        // Peso por encima del máximo absoluto (50 kg por defecto) → 400, en la compra y en el registro posterior
        mvc.perform(post("/api/v1/tickets/{id}/baggage", ticket2Id)
                        .header("Authorization", bearer(clerk))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new BaggageCreateRequest(new BigDecimal("60"), null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("equipaje")));
        purchase(pax, paxId, 3, new BaggageCreateRequest(new BigDecimal("50.5"), null))
                .andExpect(status().isBadRequest());
    }

    // ---------- Helpers ----------

    private Trip newTrip(int daysAhead) {
        LocalDate date = LocalDate.now().plusDays(daysAhead);
        return tripRepository.save(Trip.builder()
                .route(route)
                .bus(bus)
                .tripDate(date)
                .departureTime(date.atTime(12, 0))
                .arrivalEta(date.atTime(14, 0))
                .status(Trip.TripStatus.SCHEDULED)
                .build());
    }

    private void setTripStatus(Trip target, Trip.TripStatus status) {
        Trip managed = tripRepository.findById(target.getId()).orElseThrow();
        managed.setStatus(status);
        tripRepository.save(managed);
        flushAndClear();
    }

    private void assignDriver(String driverEmail) {
        assignmentRepository.save(Assignment.builder()
                .trip(tripRepository.findById(trip.getId()).orElseThrow())
                .driver(userRepository.findByEmail(driverEmail).orElseThrow())
                .checklistOk(true)
                .soatValid(true)
                .revisionValid(true)
                .build());
        flushAndClear();
    }

    private JsonNode createParcel(String clerkToken, String receiverPhone) throws Exception {
        String body = mvc.perform(post("/api/v1/parcels")
                        .header("Authorization", bearer(clerkToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new ParcelCreateRequest(
                                trip.getId(), "Remitente", "3001", "Destinatario", receiverPhone,
                                stopA.getId(), null, stopC.getId(), null,
                                new BigDecimal("15000"), new BigDecimal("2.5"), "Caja con documentos"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.deliveryOtp").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        return om.readTree(body);
    }

    private ResultActions parcelStatus(String token, String code, Parcel.ParcelStatus status,
                                       String otp, String photo) throws Exception {
        return mvc.perform(put("/api/v1/parcels/{code}/status", code)
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(new ParcelStatusUpdateRequest(code, status, otp, photo))));
    }

    private ResultActions deliver(String token, String code, String otp, String photo) throws Exception {
        return mvc.perform(post("/api/v1/parcels/{code}/deliver", code)
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(new ParcelStatusUpdateRequest(
                        code, Parcel.ParcelStatus.DELIVERED, otp, photo))));
    }

    private ResultActions purchase(String token, Long passengerId, int seat, BaggageCreateRequest baggage)
            throws Exception {
        TicketCreateRequest request = new TicketCreateRequest(
                trip.getId(), passengerId, seat,
                stopA.getId(), stopA.getName(), stopA.getOrder(),
                stopC.getId(), stopC.getName(), stopC.getOrder(),
                new BigDecimal("50000"), Ticket.PaymentMethod.CASH, baggage, null);
        return mvc.perform(post("/api/v1/trips/{tripId}/tickets", trip.getId())
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(request)));
    }

    private ResultActions summary(String token) throws Exception {
        return mvc.perform(get("/api/v1/trips/{id}/baggage", trip.getId()).header("Authorization", bearer(token)))
                .andExpect(status().isOk());
    }

    private JsonNode json(ResultActions result) throws Exception {
        return om.readTree(result.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
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
