package com.web.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.dto.auth.Login.LoginRequest;
import com.web.dto.auth.Login.RegisterRequest;
import com.web.dto.auth.User.PasswordChangeRequest;
import com.web.dto.catalog.FareRule.FareRuleCreateRequest;
import com.web.dto.catalog.FareRule.FareRuleUpdateRequest;
import com.web.dto.dispatch.Assignment.AssignmentCreateRequest;
import com.web.dto.dispatch.OverbookingPolicy.OverbookingPolicyCreateRequest;
import com.web.dto.incident.IncidentCreateRequest;
import com.web.dto.ticket.TicketCreateRequest;
import com.web.dto.trip.TripUpdateRequest;
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
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Casos de uso de administración y operación: tarifas, usuarios, incidentes, reprogramación,
// asignaciones y % de overbooking por ruta y franja (HTTP + JWT + BD)
class AdminFeaturesIntegrationTest extends BaseIntegrationTest {

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
    private IncidentRepository incidentRepository;

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
    private LocalDate date;

    @BeforeEach
    void setUp() {
        route = routeRepository.save(Route.builder()
                .code("ADM-" + (System.nanoTime() % 1_000_000_000L))
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
        trip = newTrip(bus, date.atTime(12, 0));
        flushAndClear();
    }

    // ---------- 1. Tarifas por tramo ----------

    @Test
    void fareRule_createdByAdmin_isPublicAndAppliedOnPurchase() throws Exception {
        String adminToken = staff("admin@admintest.com", User.Role.ADMIN);
        String paxToken = registerAndLogin("pax@admintest.com");

        // Tarifa del tramo A→C: 30.000 sin precio dinámico y 50 % para estudiantes
        String created = mvc.perform(post("/api/v1/routes/{id}/fares", route.getId())
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new FareRuleCreateRequest(stopA.getId(), stopC.getId(),
                                new BigDecimal("30000"), Map.of("student", 50), false))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.discounts.STUDENT").value(50))
                .andReturn().getResponse().getContentAsString();
        long fareId = om.readTree(created).get("id").asLong();

        // Consulta pública
        mvc.perform(get("/api/v1/routes/{id}/fares", route.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].fromStopId").value(stopA.getId()));

        // Mismo tramo otra vez → 409; tramo invertido → 400; un pasajero no puede crear tarifas
        mvc.perform(post("/api/v1/routes/{id}/fares", route.getId())
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new FareRuleCreateRequest(stopA.getId(), stopC.getId(),
                                new BigDecimal("1000"), null, null))))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/routes/{id}/fares", route.getId())
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new FareRuleCreateRequest(stopC.getId(), stopA.getId(),
                                new BigDecimal("1000"), null, null))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/routes/{id}/fares", route.getId())
                        .header("Authorization", bearer(paxToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new FareRuleCreateRequest(stopA.getId(), stopB.getId(),
                                new BigDecimal("1000"), null, null))))
                .andExpect(status().isForbidden());

        // La compra usa la tarifa del tramo y su descuento de estudiante
        Long paxId = userId("pax@admintest.com");
        purchase(paxToken, paxId, 1, "STUDENT").andExpect(status().isCreated())
                .andExpect(jsonPath("$.price").value(15000.00));

        // Tras actualizar el precio, la siguiente compra usa el nuevo valor
        mvc.perform(put("/api/v1/fares/{id}", fareId)
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new FareRuleUpdateRequest(null, null,
                                new BigDecimal("40000"), null, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.basePrice").value(40000));
        purchase(paxToken, paxId, 2, null).andExpect(status().isCreated())
                .andExpect(jsonPath("$.price").value(40000.00));

        mvc.perform(delete("/api/v1/fares/{id}", fareId).header("Authorization", bearer(adminToken)))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/routes/{id}/fares", route.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    // ---------- 2. Gestión de usuarios ----------

    @Test
    void users_adminManagesStatusAndRole_andDeactivatedTokenStopsWorking() throws Exception {
        String adminToken = staff("admin@admintest.com", User.Role.ADMIN);
        String driverToken = staff("driver@admintest.com", User.Role.DRIVER);
        Long driverId = userId("driver@admintest.com");
        Long adminId = userId("admin@admintest.com");

        // El token del conductor funciona mientras está activo
        mvc.perform(get("/api/v1/users/me").header("Authorization", bearer(driverToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("driver@admintest.com"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        // Listado filtrado sin hash de contraseña
        mvc.perform(get("/api/v1/admin/users").param("role", "DRIVER").param("status", "ACTIVE")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.email == 'driver@admintest.com')]").exists())
                .andExpect(jsonPath("$[0].passwordHash").doesNotExist());

        // Un no-ADMIN no gestiona usuarios; el ADMIN no puede desactivarse ni cambiarse el rol
        mvc.perform(get("/api/v1/admin/users").header("Authorization", bearer(driverToken)))
                .andExpect(status().isForbidden());
        patchStatus(adminToken, adminId, "INACTIVE").andExpect(status().isBadRequest());
        mvc.perform(patch("/api/v1/admin/users/{id}/role", adminId)
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("role", "PASSENGER"))))
                .andExpect(status().isBadRequest());

        // Cambio de rol: el conductor pasa a despachador y el nuevo rol aplica de inmediato
        mvc.perform(patch("/api/v1/admin/users/{id}/role", driverId)
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("role", "DISPATCHER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("DISPATCHER"));
        mvc.perform(get("/api/v1/assignments").header("Authorization", bearer(driverToken)))
                .andExpect(status().isOk());

        // Al desactivarlo, su token deja de servir y ya no puede iniciar sesión
        patchStatus(adminToken, driverId, "INACTIVE").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"));
        mvc.perform(get("/api/v1/users/me").header("Authorization", bearer(driverToken)))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new LoginRequest("driver@admintest.com", "secreto1"))))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/users").param("status", "INACTIVE")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.email == 'driver@admintest.com')]").exists());

        // Reactivado, vuelve a operar con el mismo token
        patchStatus(adminToken, driverId, "ACTIVE").andExpect(status().isOk());
        mvc.perform(get("/api/v1/users/me").header("Authorization", bearer(driverToken)))
                .andExpect(status().isOk());
    }

    @Test
    void users_changeOwnPassword_requiresCurrentPassword() throws Exception {
        String paxToken = registerAndLogin("Pax.Mixed@AdminTest.com");

        // El email se normalizó a minúsculas en el registro
        mvc.perform(get("/api/v1/users/me").header("Authorization", bearer(paxToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("pax.mixed@admintest.com"));

        changePassword(paxToken, "incorrecta", "nuevaClave123").andExpect(status().isBadRequest());
        changePassword(paxToken, "secreto1", "corta").andExpect(status().isBadRequest());
        changePassword(paxToken, "secreto1", "nuevaClave123").andExpect(status().isNoContent());

        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new LoginRequest("pax.mixed@admintest.com", "secreto1"))))
                .andExpect(status().isUnauthorized());
        login("pax.mixed@admintest.com", "nuevaClave123");

        mvc.perform(put("/api/v1/users/me/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new PasswordChangeRequest("a", "bbbbbbbbb"))))
                .andExpect(status().isUnauthorized());
    }

    // ---------- 3. Incidentes ----------

    @Test
    void incidents_staffReports_dispatcherFilters_andDepartedTripKeepsItsStatus() throws Exception {
        String driverToken = staff("driver@admintest.com", User.Role.DRIVER);
        String clerkToken = staff("clerk@admintest.com", User.Role.CLERK);
        String dispatcherToken = staff("disp@admintest.com", User.Role.DISPATCHER);
        String paxToken = registerAndLogin("pax@admintest.com");

        // Un incidente VEHICLE sobre un viaje en ruta solo se registra: el viaje sigue DEPARTED
        Trip departed = tripRepository.findById(trip.getId()).orElseThrow();
        departed.setStatus(Trip.TripStatus.DEPARTED);
        tripRepository.save(departed);
        flushAndClear();

        reportIncident(driverToken, new IncidentCreateRequest(Incident.IncidentType.VEHICLE,
                Incident.EntityType.TRIP, trip.getId(), "Falla en el sistema de frenos"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reportedById").value(userId("driver@admintest.com")))
                .andExpect(jsonPath("$.reportedByName").value("Staff DRIVER"));
        reportIncident(clerkToken, new IncidentCreateRequest(Incident.IncidentType.SECURITY,
                Incident.EntityType.TRIP, trip.getId(), "Objeto sospechoso en bodega"))
                .andExpect(status().isCreated());
        flushAndClear();
        assertThat(tripRepository.findById(trip.getId()).orElseThrow().getStatus()).isEqualTo(Trip.TripStatus.DEPARTED);

        // Entidad inexistente → 404; un pasajero no reporta → 403
        reportIncident(driverToken, new IncidentCreateRequest(Incident.IncidentType.DELIVERY_FAIL,
                Incident.EntityType.PARCEL, 987654321L, "No existe")).andExpect(status().isNotFound());
        reportIncident(paxToken, new IncidentCreateRequest(Incident.IncidentType.SECURITY,
                Incident.EntityType.TRIP, trip.getId(), "Pasajero")).andExpect(status().isForbidden());

        // Filtros del despachador
        String today = LocalDate.now().toString();
        mvc.perform(get("/api/v1/incidents").header("Authorization", bearer(dispatcherToken))
                        .param("entityType", "TRIP").param("entityId", trip.getId().toString())
                        .param("from", today).param("to", today))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));
        mvc.perform(get("/api/v1/incidents").header("Authorization", bearer(dispatcherToken))
                        .param("type", "VEHICLE").param("entityId", trip.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].description").value("Falla en el sistema de frenos"));
        mvc.perform(get("/api/v1/incidents").header("Authorization", bearer(dispatcherToken))
                        .param("entityId", trip.getId().toString())
                        .param("from", LocalDate.now().plusDays(1).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get("/api/v1/incidents").header("Authorization", bearer(driverToken)))
                .andExpect(status().isForbidden());
    }

    // ---------- 4. Reprogramación de viajes ----------

    @Test
    void reschedule_busBusyReturns409_freeBusMovesTrip_andOnlyScheduledTrips() throws Exception {
        String adminToken = staff("admin@admintest.com", User.Role.ADMIN);
        String paxToken = registerAndLogin("pax@admintest.com");
        Bus busyBus = newBus(40);
        newTrip(busyBus, date.atTime(6, 0));
        Bus smallBus = newBus(10);
        Bus freeBus = newBus(40);
        flushAndClear();

        // Bus con otro viaje ese día → 409
        reschedule(adminToken, new TripUpdateRequest(null, null, busyBus.getId()))
                .andExpect(status().isConflict());

        // Hay un tiquete en la silla 20: un bus de 10 sillas no sirve → 409
        purchase(paxToken, userId("pax@admintest.com"), 20, null).andExpect(status().isCreated());
        reschedule(adminToken, new TripUpdateRequest(null, null, smallBus.getId()))
                .andExpect(status().isConflict());

        // Llegada anterior a la salida → 400
        reschedule(adminToken, new TripUpdateRequest(date.atTime(15, 0), date.atTime(14, 0), null))
                .andExpect(status().isBadRequest());

        // Bus libre y un día después: la fecha del viaje sigue a la salida
        LocalDateTime newDeparture = date.plusDays(1).atTime(10, 0);
        reschedule(adminToken, new TripUpdateRequest(newDeparture, newDeparture.plusHours(2), freeBus.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.busId").value(freeBus.getId()))
                .andExpect(jsonPath("$.tripDate").value(date.plusDays(1).toString()))
                .andExpect(jsonPath("$.status").value("SCHEDULED"));
        flushAndClear();
        Trip moved = tripRepository.findById(trip.getId()).orElseThrow();
        assertThat(moved.getTripDate()).isEqualTo(date.plusDays(1));
        assertThat(moved.getDepartureTime()).isEqualTo(newDeparture);

        // El bus original queda libre ese día y el nuevo ocupado
        mvc.perform(get("/api/v1/buses/available").param("date", date.plusDays(1).toString())
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + freeBus.getId() + ")]").doesNotExist())
                .andExpect(jsonPath("$[?(@.id == " + bus.getId() + ")]").exists());

        // Un pasajero no reprograma; un viaje en abordaje no se reprograma (422)
        reschedule(paxToken, new TripUpdateRequest(newDeparture.plusHours(1), null, null))
                .andExpect(status().isForbidden());
        moved.setStatus(Trip.TripStatus.BOARDING);
        tripRepository.save(moved);
        flushAndClear();
        reschedule(adminToken, new TripUpdateRequest(newDeparture.plusHours(1), newDeparture.plusHours(3), null))
                .andExpect(status().isUnprocessableEntity());
    }

    // ---------- 5. Asignaciones del conductor y del despachador ----------

    @Test
    void assignments_driverSeesHisOwn_dispatcherSeesTheOnesHeMade() throws Exception {
        String driverToken = staff("driver@admintest.com", User.Role.DRIVER);
        String otherDriverToken = staff("driver2@admintest.com", User.Role.DRIVER);
        String dispatcherToken = staff("disp@admintest.com", User.Role.DISPATCHER);

        mvc.perform(post("/api/v1/trips/{tripId}/assign", trip.getId())
                        .header("Authorization", bearer(dispatcherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new AssignmentCreateRequest(
                                trip.getId(), userId("driver@admintest.com"), userId("disp@admintest.com")))))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/v1/assignments/me").param("date", date.toString())
                        .header("Authorization", bearer(driverToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].tripId").value(trip.getId()));
        mvc.perform(get("/api/v1/assignments/me").param("date", date.plusDays(1).toString())
                        .header("Authorization", bearer(driverToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get("/api/v1/assignments/me").header("Authorization", bearer(otherDriverToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        mvc.perform(get("/api/v1/assignments").param("date", date.toString())
                        .header("Authorization", bearer(dispatcherToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].dispatcherId").value(userId("disp@admintest.com")));
        mvc.perform(get("/api/v1/assignments").header("Authorization", bearer(dispatcherToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.tripId == " + trip.getId() + ")]").exists());

        mvc.perform(get("/api/v1/assignments").header("Authorization", bearer(driverToken)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/assignments/me").header("Authorization", bearer(dispatcherToken)))
                .andExpect(status().isForbidden());
    }

    // ---------- 6. % de overbooking por ruta y franja horaria ----------

    @Test
    void overbookingPolicy_ofTheRouteAndHour_isUsedWhenApprovingExtraSeats() throws Exception {
        String dispatcherToken = staff("disp@admintest.com", User.Role.DISPATCHER);
        String adminToken = staff("admin@admintest.com", User.Role.ADMIN);
        String paxToken = registerAndLogin("pax@admintest.com");

        // Viaje lleno (10/10) que sale en 20 minutos
        Bus smallBus = newBus(10);
        LocalDateTime departure = LocalDateTime.now().plusMinutes(20).withSecond(0).withNano(0);
        trip = newTrip(smallBus, departure);
        flushAndClear();
        Long paxId = userId("pax@admintest.com");
        for (int seat = 1; seat <= 10; seat++) {
            purchase(paxToken, paxId, seat, null).andExpect(status().isCreated());
        }

        // Sin política: 5 % de 10 sillas = 0 → no se aprueba
        approveOverbooking(dispatcherToken).andExpect(status().isForbidden());

        // Política de la ruta para la franja de la hora de salida: 10 % → 1 silla
        int hour = departure.getHour();
        mvc.perform(post("/api/v1/routes/{id}/overbooking-policies", route.getId())
                        .header("Authorization", bearer(dispatcherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new OverbookingPolicyCreateRequest(hour, hour + 1,
                                new BigDecimal("0.10")))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.createdById").value(userId("disp@admintest.com")));

        // Franja que se solapa → 409; fin no posterior al inicio → 400
        mvc.perform(post("/api/v1/routes/{id}/overbooking-policies", route.getId())
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new OverbookingPolicyCreateRequest(0, 24,
                                new BigDecimal("0.20")))))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/routes/{id}/overbooking-policies", route.getId())
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new OverbookingPolicyCreateRequest(10, 10,
                                new BigDecimal("0.20")))))
                .andExpect(status().isBadRequest());

        approveOverbooking(dispatcherToken).andExpect(status().isOk())
                .andExpect(jsonPath("$.maxExtraSeats").value(1))
                .andExpect(jsonPath("$.approvedSeatNumber").value(11));
        purchase(paxToken, paxId, 11, null).andExpect(status().isCreated());
        approveOverbooking(dispatcherToken).andExpect(status().isForbidden());

        // Consulta y eliminación de la política
        String list = mvc.perform(get("/api/v1/routes/{id}/overbooking-policies", route.getId())
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andReturn().getResponse().getContentAsString();
        long policyId = om.readTree(list).get(0).get("id").asLong();
        mvc.perform(delete("/api/v1/overbooking-policies/{id}", policyId).header("Authorization", bearer(paxToken)))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/overbooking-policies/{id}", policyId).header("Authorization", bearer(dispatcherToken)))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/routes/{id}/overbooking-policies", route.getId())
                        .header("Authorization", bearer(dispatcherToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    // ---------- Helpers ----------

    private Bus newBus(int capacity) {
        return busRepository.save(Bus.builder()
                .plate("ADM" + (System.nanoTime() % 100_000_000L))
                .capacity(capacity)
                .amenities(new HashMap<>())
                .status(Bus.BusStatus.ACTIVE)
                .build());
    }

    private Trip newTrip(Bus tripBus, LocalDateTime departure) {
        return tripRepository.save(Trip.builder()
                .route(route)
                .bus(tripBus)
                .tripDate(departure.toLocalDate())
                .departureTime(departure)
                .arrivalEta(departure.plusHours(2))
                .status(Trip.TripStatus.SCHEDULED)
                .build());
    }

    private ResultActions purchase(String token, Long passengerId, int seat, String passengerType) throws Exception {
        TicketCreateRequest request = new TicketCreateRequest(
                trip.getId(), passengerId, seat,
                stopA.getId(), stopA.getName(), stopA.getOrder(),
                stopC.getId(), stopC.getName(), stopC.getOrder(),
                new BigDecimal("1"), Ticket.PaymentMethod.CASH, null, passengerType);
        return mvc.perform(post("/api/v1/trips/{tripId}/tickets", trip.getId())
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(request)));
    }

    private ResultActions patchStatus(String token, Long id, String status) throws Exception {
        return mvc.perform(patch("/api/v1/admin/users/{id}/status", id)
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("status", status))));
    }

    private ResultActions changePassword(String token, String current, String next) throws Exception {
        return mvc.perform(put("/api/v1/users/me/password")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(new PasswordChangeRequest(current, next))));
    }

    private ResultActions reportIncident(String token, IncidentCreateRequest request) throws Exception {
        return mvc.perform(post("/api/v1/incidents")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(request)));
    }

    private ResultActions reschedule(String token, TripUpdateRequest request) throws Exception {
        return mvc.perform(put("/api/v1/trips/{id}", trip.getId())
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(request)));
    }

    private ResultActions approveOverbooking(String token) throws Exception {
        return mvc.perform(post("/api/v1/trips/{id}/overbooking/approve", trip.getId())
                .header("Authorization", bearer(token)));
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
        JsonNode json = om.readTree(body);
        return json.get("token").asText();
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
