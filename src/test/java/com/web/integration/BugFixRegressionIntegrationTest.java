package com.web.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.dto.auth.Login.LoginRequest;
import com.web.dto.auth.Login.RegisterRequest;
import com.web.dto.catalog.Stop.StopCreateRequest;
import com.web.dto.dispatch.Assignment.AssignmentCreateRequest;
import com.web.dto.dispatch.Assignment.AssignmentUpdateRequest;
import com.web.dto.parcel.ParcelCreateRequest;
import com.web.dto.payment.PaymentConfirmRequest;
import com.web.dto.ticket.TicketCreateRequest;
import com.web.dto.ticket.reservations.SeatHoldRequest;
import com.web.entity.*;
import com.web.integration.userstories.BaseIntegrationTest;
import com.web.repository.*;
import com.web.service.ticket.TicketServiceImpl;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Pruebas de regresión de extremo a extremo (HTTP + JWT + BD real) para los errores corregidos en la revisión
class BugFixRegressionIntegrationTest extends BaseIntegrationTest {

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
    private SeatHoldRepository seatHoldRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private AssignmentRepository assignmentRepository;

    @Autowired
    private TicketServiceImpl ticketService;

    private Route route;
    private Stop stopA;
    private Stop stopB;
    private Stop stopC;
    private Trip trip;

    @BeforeEach
    void setUp() {
        // Los datos semilla (V2) ya ocupan los primeros IDs de paradas: así los IDs nunca coinciden
        // con el orden de las paradas, que es justo lo que ocultaba el error de solapamiento
        route = routeRepository.save(Route.builder()
                .code("REG-" + System.nanoTime())
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

        Bus bus = busRepository.save(Bus.builder()
                .plate("REG" + (System.nanoTime() % 100000))
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

        assertThat(stopA.getId()).isNotEqualTo(stopA.getOrder().longValue());

        // Cada petición HTTP real usa un contexto de persistencia nuevo: se simula vaciando el actual
        entityManager.flush();
        entityManager.clear();
    }

    // Verifica que el mismo asiento no se venda dos veces en tramos que se solapan
    @Test
    void purchase_withOverlappingSegment_shouldReturn409() throws Exception {
        String token1 = registerAndLogin("pax1@test.com");
        Long pax1 = userId("pax1@test.com");
        String token2 = registerAndLogin("pax2@test.com");
        Long pax2 = userId("pax2@test.com");

        // pax1 compra el asiento 5 en el tramo B -> C
        purchase(token1, pax1, 5, stopB, stopC).andExpect(status().isCreated());

        // pax2 intenta A -> C (se solapa con B -> C): debe rechazarse
        purchase(token2, pax2, 5, stopA, stopC)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("no está disponible")));

        // pax2 compra A -> B (contiguo, no se solapa): debe permitirse
        purchase(token2, pax2, 5, stopA, stopB).andExpect(status().isCreated());

        assertThat(ticketRepository.findByTripIdAndSeatNumber(trip.getId(), 5)).hasSize(2);
    }

    // Verifica que un asiento fuera de la capacidad del bus se rechace (sin overbooking aprobado: 403)
    @Test
    void purchase_withSeatOutsideCapacity_shouldReturn403() throws Exception {
        String token = registerAndLogin("pax@test.com");
        purchase(token, userId("pax@test.com"), 999, stopA, stopC).andExpect(status().isForbidden());
        purchase(token, userId("pax@test.com"), 0, stopA, stopC).andExpect(status().isConflict());
    }

    // Verifica el flujo hold -> compra (antes fallaba con 500 por el CHECK de seat_holds)
    @Test
    void holdThenPurchase_shouldSucceedAndMarkHoldAsSold() throws Exception {
        String token = registerAndLogin("pax@test.com");
        Long paxId = userId("pax@test.com");

        mvc.perform(post("/api/v1/trips/{tripId}/seats/{seat}/hold", trip.getId(), 7)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new SeatHoldRequest(paxId, stopA.getId(), stopC.getId()))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("HOLD"));

        purchase(token, paxId, 7, stopA, stopC).andExpect(status().isCreated());

        // Forzar el UPDATE en la BD para que se evalúe la restricción CHECK
        entityManager.flush();
        entityManager.clear();

        assertThat(seatHoldRepository.findAll())
                .filteredOn(h -> h.getTrip().getId().equals(trip.getId()) && h.getSeatNumber() == 7)
                .extracting(SeatHold::getStatus)
                .containsExactly(SeatHold.HoldStatus.SOLD);
    }

    // Verifica que el registro público no permita crear administradores: 403 explícito, no una degradación silenciosa
    @Test
    void publicRegister_withAdminRole_shouldReturn403AndNotCreateUser() throws Exception {
        RegisterRequest request = new RegisterRequest("Intruso", "intruso@test.com", "300", "secreto1", User.Role.ADMIN);

        mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        assertThat(userRepository.findByEmail("intruso@test.com")).isEmpty();
    }

    // Verifica que el registro público pidiendo explícitamente PASSENGER sí funcione
    @Test
    void publicRegister_withPassengerRole_shouldCreatePassenger() throws Exception {
        RegisterRequest request = new RegisterRequest("Pasajero", "pasajero@test.com", "300", "secreto1", User.Role.PASSENGER);

        mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("PASSENGER"));
    }

    // Verifica que un ADMIN autenticado sí pueda registrar usuarios con otros roles
    @Test
    void register_byAuthenticatedAdmin_shouldKeepRequestedRole() throws Exception {
        createUser(new RegisterRequest("Admin", "admin@test.com", "300", "secreto1", User.Role.ADMIN));
        String adminToken = login("admin@test.com", "secreto1");

        mvc.perform(post("/api/v1/auth/register")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(
                                new RegisterRequest("Conductor", "driver@test.com", "300", "secreto1", User.Role.DRIVER))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("DRIVER"));
    }

    // Verifica que el rastreo público no exponga el OTP de entrega
    @Test
    void publicParcelTracking_shouldNotExposeOtp() throws Exception {
        createUser(new RegisterRequest("Taquilla", "clerk@test.com", "300", "secreto1", User.Role.CLERK));
        String clerkToken = login("clerk@test.com", "secreto1");

        ParcelCreateRequest request = new ParcelCreateRequest(
                trip.getId(), "Remitente", "3001", "Destinatario", "3002",
                stopA.getId(), null, stopC.getId(), null,
                new BigDecimal("15000"), new BigDecimal("2.5"), "Caja");

        String body = mvc.perform(post("/api/v1/parcels")
                        .header("Authorization", "Bearer " + clerkToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(request)))
                .andExpect(status().isCreated())
                // La taquilla sí recibe el OTP para entregárselo al destinatario
                .andExpect(jsonPath("$.deliveryOtp").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        String code = om.readTree(body).get("code").asText();

        mvc.perform(get("/api/v1/parcels/{code}/track", code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.deliveryOtp").doesNotExist());
    }

    // Verifica que una encomienda con paradas en sentido inverso se rechace
    @Test
    void createParcel_withReversedStops_shouldReturn400() throws Exception {
        createUser(new RegisterRequest("Taquilla", "clerk@test.com", "300", "secreto1", User.Role.CLERK));
        String clerkToken = login("clerk@test.com", "secreto1");

        ParcelCreateRequest request = new ParcelCreateRequest(
                trip.getId(), "Remitente", "3001", "Destinatario", "3002",
                stopC.getId(), null, stopA.getId(), null,
                new BigDecimal("15000"), null, null);

        mvc.perform(post("/api/v1/parcels")
                        .header("Authorization", "Bearer " + clerkToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    // Verifica que un rol sin permiso reciba 403 y no 500 cuando lo bloquea @PreAuthorize
    @Test
    void passengerCallingStaffEndpoint_shouldReturn403() throws Exception {
        String token = registerAndLogin("pax@test.com");

        mvc.perform(get("/api/v1/tickets/qr/{qr}", "cualquiera").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/trips/{id}/passengers", trip.getId())
                        .param("fromStopId", stopA.getId().toString())
                        .param("toStopId", stopC.getId().toString())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    // Verifica que un JSON mal formado devuelva 400 y un error interno no exponga detalles
    @Test
    void malformedJson_shouldReturn400() throws Exception {
        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{mal formado"))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Sin email\",\"phone\":\"300\",\"password\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.email").exists());
    }

    // Verifica que un usuario desactivado no pueda seguir usando su token
    @Test
    void deactivatedUser_withValidToken_shouldBeRejected() throws Exception {
        String token = registerAndLogin("pax@test.com");
        mvc.perform(get("/api/v1/tickets/my-tickets").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        User user = userRepository.findByEmail("pax@test.com").orElseThrow();
        user.setStatus(User.Status.INACTIVE);
        userRepository.saveAndFlush(user);

        mvc.perform(get("/api/v1/tickets/my-tickets").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    // Verifica el flujo completo de salida: asignar, aprobar checklist (endpoint nuevo), abordar y partir
    @Test
    void dispatchFlow_withChecklistApprovedThroughApi_shouldDepart() throws Exception {
        User driver = createUser(new RegisterRequest("Conductor", "driver@test.com", "300", "secreto1", User.Role.DRIVER));
        User dispatcher = createUser(new RegisterRequest("Despachador", "disp@test.com", "300", "secreto1", User.Role.DISPATCHER));
        String driverToken = login("driver@test.com", "secreto1");
        String dispatcherToken = login("disp@test.com", "secreto1");

        mvc.perform(post("/api/v1/trips/{tripId}/assign", trip.getId())
                        .header("Authorization", "Bearer " + dispatcherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(
                                new AssignmentCreateRequest(trip.getId(), driver.getId(), dispatcher.getId()))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.checklistOk").value(false));

        mvc.perform(post("/api/v1/trips/{tripId}/boarding/open", trip.getId())
                        .header("Authorization", "Bearer " + dispatcherToken))
                .andExpect(status().isOk());

        // Sin checklist aprobado no puede partir
        mvc.perform(post("/api/v1/trips/{tripId}/depart", trip.getId())
                        .header("Authorization", "Bearer " + driverToken))
                .andExpect(status().isBadRequest());

        // El conductor puede consultar la asignación pero no modificarla
        mvc.perform(get("/api/v1/trips/{tripId}/assignment", trip.getId())
                        .header("Authorization", "Bearer " + driverToken))
                .andExpect(status().isOk());
        mvc.perform(put("/api/v1/trips/{tripId}/assignment", trip.getId())
                        .header("Authorization", "Bearer " + driverToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new AssignmentUpdateRequest(null, true, true, true))))
                .andExpect(status().isForbidden());

        mvc.perform(put("/api/v1/trips/{tripId}/assignment", trip.getId())
                        .header("Authorization", "Bearer " + dispatcherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new AssignmentUpdateRequest(null, true, true, true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checklistOk").value(true))
                .andExpect(jsonPath("$.soatValid").value(true))
                .andExpect(jsonPath("$.revisionValid").value(true));

        mvc.perform(post("/api/v1/trips/{tripId}/depart", trip.getId())
                        .header("Authorization", "Bearer " + driverToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DEPARTED"));
    }

    // Verifica que agregar una parada devuelva la ruta incluyendo la parada nueva
    @Test
    void addStop_shouldReturnRouteIncludingNewStop() throws Exception {
        createUser(new RegisterRequest("Admin", "admin@test.com", "300", "secreto1", User.Role.ADMIN));
        String adminToken = login("admin@test.com", "secreto1");

        mvc.perform(post("/api/v1/routes/{routeId}/stops", route.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new StopCreateRequest(route.getId(), "Puerto Colombia", 4, null, null))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.stops.length()").value(4))
                .andExpect(jsonPath("$.stops[3].name").value("Puerto Colombia"));
    }

    // Verifica que no se pueda cancelar un ticket de un viaje que ya salió
    @Test
    void cancelTicket_afterDeparture_shouldReturn400() throws Exception {
        String token = registerAndLogin("pax@test.com");
        User pax = userRepository.findByEmail("pax@test.com").orElseThrow();

        LocalDateTime past = LocalDateTime.now().minusHours(1);
        trip.setDepartureTime(past);
        trip.setTripDate(past.toLocalDate());
        tripRepository.save(trip);

        Ticket ticket = ticketRepository.save(Ticket.builder()
                .trip(trip)
                .passenger(pax)
                .seatNumber(3)
                .fromStop(stopA)
                .toStop(stopC)
                .price(new BigDecimal("50000"))
                .paymentMethod(Ticket.PaymentMethod.CASH)
                .status(Ticket.TicketStatus.SOLD)
                .qrCode("QR-CANCEL-" + System.nanoTime())
                .build());

        mvc.perform(post("/api/v1/tickets/{id}/cancel", ticket.getId()).header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("ya salió")));
    }

    // Verifica que eliminar una ruta sin viajes futuros la desactive (borrado lógico)
    @Test
    void deleteRoute_withoutFutureTrips_shouldDeactivateIt() throws Exception {
        createUser(new RegisterRequest("Admin", "admin@test.com", "300", "secreto1", User.Role.ADMIN));
        String adminToken = login("admin@test.com", "secreto1");

        // Con un viaje programado no se puede eliminar (conflicto)
        mvc.perform(delete("/api/v1/routes/{id}", route.getId()).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isConflict());

        trip.setTripDate(LocalDate.now().minusDays(10));
        trip.setDepartureTime(LocalDate.now().minusDays(10).atTime(12, 0));
        tripRepository.saveAndFlush(trip);

        mvc.perform(delete("/api/v1/routes/{id}", route.getId()).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());

        // La ruta sigue existiendo (los viajes históricos la referencian) pero queda inactiva:
        // el público ya no la ve y un ADMIN la consulta con includeInactive
        mvc.perform(get("/api/v1/routes/{id}", route.getId()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/routes/{id}", route.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .param("includeInactive", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isActive").value(false));
    }

    // ---------- Decisiones pendientes resueltas ----------

    // Holds por tramo: dos pasajeros pueden reservar el mismo asiento en tramos que no se solapan
    @Test
    void segmentHolds_onNonOverlappingSegments_shouldCoexist() throws Exception {
        String token1 = registerAndLogin("hold1@test.com");
        Long pax1 = userId("hold1@test.com");
        String token2 = registerAndLogin("hold2@test.com");
        Long pax2 = userId("hold2@test.com");

        hold(token1, pax1, 8, stopA, stopB)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fromStopId").value(stopA.getId()))
                .andExpect(jsonPath("$.toStopId").value(stopB.getId()));
        hold(token2, pax2, 8, stopB, stopC).andExpect(status().isCreated());
        // A -> C se solapa con los dos holds
        hold(token2, pax2, 8, stopA, stopC).andExpect(status().isConflict());

        // pax1 compra su tramo y su hold queda como SOLD; el de pax2 sigue activo
        purchase(token1, pax1, 8, stopA, stopB).andExpect(status().isCreated());
        entityManager.flush();
        entityManager.clear();
        assertThat(seatHoldRepository.findAll())
                .filteredOn(h -> h.getTrip().getId().equals(trip.getId()) && h.getSeatNumber() == 8)
                .extracting(SeatHold::getStatus)
                .containsExactlyInAnyOrder(SeatHold.HoldStatus.SOLD, SeatHold.HoldStatus.HOLD);
    }

    // Abordaje por QR y no-show: solo quien no abordó queda como NO_SHOW
    @Test
    void boarding_thenNoShowProcess_shouldOnlyMarkUnboardedTickets() throws Exception {
        String token1 = registerAndLogin("board1@test.com");
        String token2 = registerAndLogin("board2@test.com");
        Long boardedId = ticketId(purchase(token1, userId("board1@test.com"), 1, stopA, stopC));
        Long missingId = ticketId(purchase(token2, userId("board2@test.com"), 2, stopA, stopC));

        createUser(new RegisterRequest("Conductor", "driver@test.com", "300", "secreto1", User.Role.DRIVER));
        String driverToken = login("driver@test.com", "secreto1");
        assignDriver("driver@test.com");
        String qr = ticketRepository.findById(boardedId).orElseThrow().getQrCode();

        // Un conductor que no está asignado al viaje no puede validar sus QR
        createUser(new RegisterRequest("Otro conductor", "driver2@test.com", "300", "secreto1", User.Role.DRIVER));
        board(login("driver2@test.com", "secreto1"), qr).andExpect(status().isForbidden());

        // Con el viaje aún en SCHEDULED no se puede abordar
        board(driverToken, qr).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("abordaje no está abierto")));

        Trip boardingTrip = tripRepository.findById(trip.getId()).orElseThrow();
        boardingTrip.setStatus(Trip.TripStatus.BOARDING);
        boardingTrip.setDepartureTime(LocalDateTime.now().plusMinutes(3));
        tripRepository.saveAndFlush(boardingTrip);
        entityManager.clear();

        payOnBoarding(driverToken, boardedId);
        board(driverToken, qr).andExpect(status().isOk())
                .andExpect(jsonPath("$.boardedAt").isNotEmpty());
        board(driverToken, qr).andExpect(status().isConflict());
        // Un pasajero no puede registrar abordajes
        board(token1, qr).andExpect(status().isForbidden());

        ticketService.processNoShows();
        entityManager.flush();
        entityManager.clear();

        assertThat(ticketRepository.findById(boardedId).orElseThrow().getStatus()).isEqualTo(Ticket.TicketStatus.SOLD);
        assertThat(ticketRepository.findById(missingId).orElseThrow().getStatus()).isEqualTo(Ticket.TicketStatus.NO_SHOW);
    }

    // Cancelar un viaje cancela sus tickets vendidos
    @Test
    void cancelTrip_shouldCancelSoldTickets() throws Exception {
        String token = registerAndLogin("cancel@test.com");
        Long ticket = ticketId(purchase(token, userId("cancel@test.com"), 3, stopA, stopC));
        createUser(new RegisterRequest("Admin", "admin@test.com", "300", "secreto1", User.Role.ADMIN));
        String adminToken = login("admin@test.com", "secreto1");

        mvc.perform(delete("/api/v1/trips/{id}", trip.getId()).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());

        entityManager.flush();
        entityManager.clear();
        assertThat(ticketRepository.findById(ticket).orElseThrow().getStatus()).isEqualTo(Ticket.TicketStatus.CANCELLED);
        assertThat(tripRepository.findById(trip.getId()).orElseThrow().getStatus()).isEqualTo(Trip.TripStatus.CANCELLED);
    }

    // Buses disponibles: se excluye el que ya tiene un viaje ese día
    @Test
    void availableBuses_shouldExcludeBusAlreadyScheduledThatDay() throws Exception {
        createUser(new RegisterRequest("Despachador", "disp@test.com", "300", "secreto1", User.Role.DISPATCHER));
        String token = login("disp@test.com", "secreto1");
        String plate = tripRepository.findById(trip.getId()).orElseThrow().getBus().getPlate();

        mvc.perform(get("/api/v1/buses/available")
                        .param("date", trip.getTripDate().toString())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.plate == '" + plate + "')]").isEmpty());

        mvc.perform(get("/api/v1/buses/available")
                        .param("date", trip.getTripDate().plusDays(1).toString())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.plate == '" + plate + "')]").isNotEmpty());
    }

    // Lista de pasajeros por tramo: incluye a quien atraviesa el tramo aunque su origen/destino sea otro
    @Test
    void passengersBySegment_shouldIncludePassengersCrossingTheSegment() throws Exception {
        String token = registerAndLogin("cross@test.com");
        Long pax = userId("cross@test.com");
        purchase(token, pax, 4, stopA, stopC).andExpect(status().isCreated());
        purchase(token, pax, 5, stopB, stopC).andExpect(status().isCreated());

        createUser(new RegisterRequest("Conductor", "driver@test.com", "300", "secreto1", User.Role.DRIVER));
        String driverToken = login("driver@test.com", "secreto1");

        // Sin asignación el conductor no puede ver la lista de pasajeros
        mvc.perform(get("/api/v1/trips/{tripId}/passengers", trip.getId())
                        .param("fromStopId", stopA.getId().toString())
                        .param("toStopId", stopB.getId().toString())
                        .header("Authorization", "Bearer " + driverToken))
                .andExpect(status().isForbidden());

        assignDriver("driver@test.com");

        mvc.perform(get("/api/v1/trips/{tripId}/passengers", trip.getId())
                        .param("fromStopId", stopA.getId().toString())
                        .param("toStopId", stopB.getId().toString())
                        .header("Authorization", "Bearer " + driverToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].seatNumber").value(4));
    }

    // Los usuarios semilla de V2 pueden iniciar sesión con la contraseña documentada (V5)
    @Test
    void seedUsers_shouldLoginWithDocumentedPassword() throws Exception {
        String token = login("cmbarrera@gmail.com", "Password123");
        mvc.perform(get("/api/v1/admin/config").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        login("driver1@transport.com", "Password123");
    }

    // ---------- Helpers ----------

    private ResultActions purchase(String token, Long passengerId, int seat, Stop from, Stop to) throws Exception {
        TicketCreateRequest request = new TicketCreateRequest(
                trip.getId(), passengerId, seat,
                from.getId(), from.getName(), from.getOrder(),
                to.getId(), to.getName(), to.getOrder(),
                new BigDecimal("50000"), Ticket.PaymentMethod.CASH, null, null);

        return mvc.perform(post("/api/v1/trips/{tripId}/tickets", trip.getId())
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(request)));
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

    private ResultActions hold(String token, Long userId, int seat, Stop from, Stop to) throws Exception {
        return mvc.perform(post("/api/v1/trips/{tripId}/seats/{seat}/hold", trip.getId(), seat)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(new SeatHoldRequest(userId, from.getId(), to.getId()))));
    }

    // Contraentrega: el conductor asignado cobra al subir el ticket comprado por la app
    private void payOnBoarding(String driverToken, Long ticketId) throws Exception {
        mvc.perform(post("/api/v1/payments/confirm")
                        .header("Authorization", "Bearer " + driverToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new PaymentConfirmRequest(
                                ticketId, Ticket.PaymentMethod.CASH, null, null, null))))
                .andExpect(status().isOk());
    }

    private ResultActions board(String token, String qr) throws Exception {
        return mvc.perform(post("/api/v1/tickets/qr/{qr}/board", qr).header("Authorization", "Bearer " + token));
    }

    private Long ticketId(ResultActions purchase) throws Exception {
        String body = purchase.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return om.readTree(body).get("id").asLong();
    }

    private void assignDriver(String driverEmail) {
        assignmentRepository.saveAndFlush(Assignment.builder()
                .trip(tripRepository.findById(trip.getId()).orElseThrow())
                .driver(userRepository.findByEmail(driverEmail).orElseThrow())
                .build());
    }
}
