package com.web.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.dto.auth.Login.LoginRequest;
import com.web.dto.auth.Login.RegisterRequest;
import com.web.dto.dispatch.Assignment.AssignmentCreateRequest;
import com.web.dto.dispatch.Assignment.AssignmentUpdateRequest;
import com.web.dto.parcel.ParcelCreateRequest;
import com.web.dto.payment.CashCloseRequest;
import com.web.dto.ticket.TicketCreateRequest;
import com.web.dto.ticket.reservations.SeatHoldRequest;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Propiedad de tickets, caja por cajero, reglas de despacho y protecciones del catálogo (HTTP + JWT + BD)
class AccessAndDispatchRulesIntegrationTest extends BaseIntegrationTest {

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
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    private Route route;
    private Stop stopA;
    private Stop stopB;
    private Stop stopC;
    private Trip trip;

    @BeforeEach
    void setUp() {
        route = routeRepository.save(Route.builder()
                .code("ACC-" + System.nanoTime())
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
                .plate("ACC" + (System.nanoTime() % 100000))
                .capacity(40)
                .amenities(new HashMap<>())
                .status(Bus.BusStatus.ACTIVE)
                .build());
        LocalDate date = LocalDate.now().plusDays(4);
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

    // ---------- Propiedad de los tickets ----------

    @Test
    void passenger_canOnlyBuyHoldViewAndCancelOwnTickets() throws Exception {
        String ana = registerAndLogin("ana@test.com");
        String luis = registerAndLogin("luis@test.com");
        Long anaId = userId("ana@test.com");
        Long luisId = userId("luis@test.com");

        // Comprar o reservar a nombre de otro pasajero: 403
        purchase(luis, anaId, 1).andExpect(status().isForbidden());
        hold(luis, anaId, 1).andExpect(status().isForbidden());

        Long anaTicket = ticketId(purchase(ana, anaId, 1));

        // Ver o cancelar el ticket de otro: 403; el propio sí
        mvc.perform(get("/api/v1/tickets/{id}", anaTicket).header("Authorization", bearer(luis)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/tickets/{id}/cancel", anaTicket).header("Authorization", bearer(luis)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/tickets/{id}", anaTicket).header("Authorization", bearer(ana)))
                .andExpect(status().isOk());

        // La taquilla sí vende a nombre de un pasajero
        String clerk = staff("clerk@test.com", User.Role.CLERK);
        purchase(clerk, luisId, 2).andExpect(status().isCreated());
        mvc.perform(post("/api/v1/tickets/{id}/cancel", anaTicket).header("Authorization", bearer(ana)))
                .andExpect(status().isOk());
    }

    // ---------- Holds: tope por usuario y mapa de asientos ----------

    @Test
    void holds_areLimitedPerUserAndShownAsHeldInSeatMap() throws Exception {
        String pax = registerAndLogin("holder@test.com");
        Long paxId = userId("holder@test.com");

        for (int seat = 1; seat <= 4; seat++) {
            hold(pax, paxId, seat).andExpect(status().isCreated());
        }
        hold(pax, paxId, 5).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("máximo de 4")));

        // El hold A -> C bloquea la silla 1 en el tramo A -> B: aparece HELD
        mvc.perform(get("/api/v1/trips/{id}/seats", trip.getId())
                        .param("fromStopId", stopA.getId().toString())
                        .param("toStopId", stopB.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("HELD"))
                .andExpect(jsonPath("$[0].available").value(false))
                .andExpect(jsonPath("$[9].status").value("AVAILABLE"));
    }

    // ---------- Cierre de caja por cajero ----------

    @Test
    void cashClose_shouldOnlyCountTheCashierOwnSales() throws Exception {
        String clerk1 = staff("clerk1@test.com", User.Role.CLERK);
        String clerk2 = staff("clerk2@test.com", User.Role.CLERK);
        registerAndLogin("buyer@test.com");
        Long buyer = userId("buyer@test.com");

        BigDecimal before1 = expectedCash(clerk1, "clerk1@test.com");
        BigDecimal before2 = expectedCash(clerk2, "clerk2@test.com");

        String body = purchase(clerk1, buyer, 3).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        BigDecimal price = new BigDecimal(om.readTree(body).get("price").asText());

        assertThat(expectedCash(clerk1, "clerk1@test.com")).isEqualByComparingTo(before1.add(price));
        assertThat(expectedCash(clerk2, "clerk2@test.com")).isEqualByComparingTo(before2);
    }

    // ---------- Despacho: asignación y checklist ----------

    @Test
    void assignment_requiresAvailableActiveDriver_andChecklistIsLockedAfterDeparture() throws Exception {
        String dispatcher = staff("disp@test.com", User.Role.DISPATCHER);
        staff("driver@test.com", User.Role.DRIVER);
        String driverToken = login("driver@test.com", "secreto1");
        User inactive = staff("off@test.com", User.Role.DRIVER, User.Status.INACTIVE);

        // Conductor inactivo
        assign(dispatcher, trip.getId(), inactive.getId()).andExpect(status().isBadRequest());

        // Un dispatcherId en el body distinto del despachador autenticado se rechaza (400)
        assign(dispatcher, trip.getId(), userId("driver@test.com"), userId("driver@test.com"))
                .andExpect(status().isBadRequest());

        // Sin dispatcherId queda el despachador autenticado
        assign(dispatcher, trip.getId(), userId("driver@test.com"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.dispatcherId").value(userId("disp@test.com")));

        // Otro viaje a la misma hora con el mismo conductor: 409
        Trip overlapping = tripRepository.save(Trip.builder()
                .route(routeRepository.findById(route.getId()).orElseThrow())
                .bus(busRepository.save(Bus.builder().plate("OVL" + (System.nanoTime() % 100000)).capacity(40)
                        .amenities(new HashMap<>()).status(Bus.BusStatus.ACTIVE).build()))
                .tripDate(trip.getTripDate())
                .departureTime(trip.getDepartureTime().plusMinutes(30))
                .arrivalEta(trip.getArrivalEta().plusMinutes(30))
                .status(Trip.TripStatus.SCHEDULED)
                .build());
        assign(dispatcher, overlapping.getId(), userId("driver@test.com")).andExpect(status().isConflict());

        checklist(dispatcher).andExpect(status().isOk());
        mvc.perform(post("/api/v1/trips/{id}/boarding/open", trip.getId()).header("Authorization", bearer(dispatcher)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/trips/{id}/depart", trip.getId()).header("Authorization", bearer(driverToken)))
                .andExpect(status().isOk());

        // Con el viaje ya en ruta el checklist no se puede modificar
        checklist(dispatcher).andExpect(status().isUnprocessableEntity());
    }

    // ---------- Cancelación de viaje con encomiendas ----------

    @Test
    void cancelTrip_shouldFailPendingParcels() throws Exception {
        String clerk = staff("clerk@test.com", User.Role.CLERK);
        String admin = staff("admin@test.com", User.Role.ADMIN);
        String body = mvc.perform(post("/api/v1/parcels")
                        .header("Authorization", bearer(clerk))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new ParcelCreateRequest(
                                trip.getId(), "Remitente", "3001", "Destinatario", "3002",
                                stopA.getId(), null, stopC.getId(), null,
                                new BigDecimal("15000"), new BigDecimal("2.5"), "Caja"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Long parcelId = om.readTree(body).get("id").asLong();

        mvc.perform(delete("/api/v1/trips/{id}", trip.getId()).header("Authorization", bearer(admin)))
                .andExpect(status().isNoContent());

        entityManager.flush();
        entityManager.clear();
        assertThat(parcelRepository.findById(parcelId).orElseThrow().getStatus()).isEqualTo(Parcel.ParcelStatus.FAILED);
    }

    // ---------- Catálogo y cuentas ----------

    @Test
    void removeStop_inUse_shouldReturn409() throws Exception {
        String admin = staff("admin@test.com", User.Role.ADMIN);
        String pax = registerAndLogin("stop@test.com");
        purchase(pax, userId("stop@test.com"), 1).andExpect(status().isCreated());

        mvc.perform(delete("/api/v1/routes/{routeId}/stops/{stopId}", route.getId(), stopC.getId())
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isConflict());
        // B no la usa ningún ticket, tarifa ni encomienda: se puede quitar
        mvc.perform(delete("/api/v1/routes/{routeId}/stops/{stopId}", route.getId(), stopB.getId())
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isNoContent());
    }

    @Test
    void email_shouldBeCaseInsensitive() throws Exception {
        mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new RegisterRequest(
                                "Juan", "Juan.Mixto@Test.com", "300", "secreto1", User.Role.PASSENGER))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("juan.mixto@test.com"));

        // Otra cuenta con el mismo email en otras mayúsculas: 409
        mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new RegisterRequest(
                                "Juan 2", "JUAN.MIXTO@TEST.COM", "300", "secreto1", User.Role.PASSENGER))))
                .andExpect(status().isConflict());

        login("JUAN.Mixto@test.com", "secreto1");
    }

    @Test
    void passengerList_shouldNotExposeEmail() throws Exception {
        String pax = registerAndLogin("private@test.com");
        purchase(pax, userId("private@test.com"), 1).andExpect(status().isCreated());
        String dispatcher = staff("disp@test.com", User.Role.DISPATCHER);

        mvc.perform(get("/api/v1/trips/{tripId}/passengers", trip.getId())
                        .param("fromStopId", stopA.getId().toString())
                        .param("toStopId", stopC.getId().toString())
                        .header("Authorization", bearer(dispatcher)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].passengerName").value("Pasajero"))
                .andExpect(jsonPath("$[0].passengerEmail").doesNotExist());
    }

    // ---------- Helpers ----------

    private ResultActions purchase(String token, Long passengerId, int seat) throws Exception {
        TicketCreateRequest request = new TicketCreateRequest(
                trip.getId(), passengerId, seat,
                stopA.getId(), null, null, stopC.getId(), null, null,
                new BigDecimal("50000"), Ticket.PaymentMethod.CASH, null, null);
        return mvc.perform(post("/api/v1/trips/{tripId}/tickets", trip.getId())
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(request)));
    }

    private ResultActions hold(String token, Long userId, int seat) throws Exception {
        return mvc.perform(post("/api/v1/trips/{tripId}/seats/{seat}/hold", trip.getId(), seat)
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(new SeatHoldRequest(userId, stopA.getId(), stopC.getId()))));
    }

    private ResultActions assign(String token, Long tripId, Long driverId) throws Exception {
        // Sin dispatcherId en el body: el despachador es el usuario autenticado
        return assign(token, tripId, driverId, null);
    }

    private ResultActions assign(String token, Long tripId, Long driverId, Long dispatcherId) throws Exception {
        return mvc.perform(post("/api/v1/trips/{tripId}/assign", tripId)
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(new AssignmentCreateRequest(tripId, driverId, dispatcherId))));
    }

    private ResultActions checklist(String token) throws Exception {
        return mvc.perform(put("/api/v1/trips/{tripId}/assignment", trip.getId())
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(new AssignmentUpdateRequest(null, true, true, true))));
    }

    private BigDecimal expectedCash(String token, String email) throws Exception {
        String body = mvc.perform(post("/api/v1/cash/close")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new CashCloseRequest(
                                userId(email), LocalDate.now(), null, BigDecimal.ZERO, null))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return new BigDecimal(om.readTree(body).get("expectedAmount").asText());
    }

    private Long ticketId(ResultActions purchase) throws Exception {
        String body = purchase.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return om.readTree(body).get("id").asLong();
    }

    private String staff(String email, User.Role role) throws Exception {
        createUser(new RegisterRequest("Staff " + role, email, "300", "secreto1", role));
        return login(email, "secreto1");
    }

    private User staff(String email, User.Role role, User.Status status) {
        User user = createUser(new RegisterRequest("Staff " + role, email, "300", "secreto1", role));
        user.setStatus(status);
        return userRepository.saveAndFlush(user);
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
}
