package com.web.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.dto.auth.Login.LoginRequest;
import com.web.dto.auth.Login.RegisterRequest;
import com.web.dto.notification.PlatformUpdateRequest;
import com.web.dto.ticket.TicketCreateRequest;
import com.web.entity.*;
import com.web.integration.userstories.BaseIntegrationTest;
import com.web.repository.*;
import com.web.service.notification.NotificationScheduler;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Notificaciones simuladas por WhatsApp/SMS de extremo a extremo: compra, cambio de andén, cancelación y llegada próxima
class NotificationsIntegrationTest extends BaseIntegrationTest {

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
    private NotificationRepository notificationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private NotificationScheduler notificationScheduler;

    @Autowired
    private EntityManager entityManager;

    private Stop stopA;
    private Stop stopB;
    private Stop stopC;
    private Trip trip;

    @BeforeEach
    void setUp() {
        Route route = routeRepository.save(Route.builder()
                .code("NOT-" + System.nanoTime())
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
                .plate("NOT" + (System.nanoTime() % 100000))
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

        flushAndClear();
    }

    // Historia: como pasajero quiero recibir el QR del ticket al comprar
    @Test
    void purchase_shouldNotifyPassengerWithQrSeatSegmentAndDeparture() throws Exception {
        String token = registerAndLogin("compra@test.com", "3001234567");
        JsonNode ticket = json(purchase(token, userId("compra@test.com"), 7, stopA, stopB));
        String qr = ticketRepository.findById(ticket.get("id").asLong()).orElseThrow().getQrCode();

        mvc.perform(get("/api/v1/notifications/me").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].type").value("TICKET_PURCHASED"))
                .andExpect(jsonPath("$[0].channel").value("WHATSAPP"))
                .andExpect(jsonPath("$[0].status").value("SENT"))
                .andExpect(jsonPath("$[0].recipient").value("3001234567"))
                .andExpect(jsonPath("$[0].ticketId").value(ticket.get("id").asLong()))
                .andExpect(jsonPath("$[0].tripId").value(trip.getId()))
                .andExpect(jsonPath("$[0].message").value(containsString(qr)))
                .andExpect(jsonPath("$[0].message").value(containsString("silla 7")))
                .andExpect(jsonPath("$[0].message").value(containsString("Santa Marta → Ciénaga")))
                .andExpect(jsonPath("$[0].message").value(containsString(
                        trip.getDepartureTime().format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")))));

        // Otro pasajero no ve notificaciones ajenas
        String other = registerAndLogin("otro@test.com", "6015551234");
        mvc.perform(get("/api/v1/notifications/me").header("Authorization", bearer(other)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // Una venta en taquilla notifica al pasajero, no al cajero; con teléfono fijo se usa SMS
    @Test
    void boxOfficeSale_shouldNotifyPassengerBySmsWhenLandline() throws Exception {
        registerAndLogin("fijo@test.com", "6015551234");
        String clerkToken = staff("clerk@test.com", User.Role.CLERK);

        purchase(clerkToken, userId("fijo@test.com"), 3, stopA, stopC).andExpect(status().isCreated());

        flushAndClear();
        List<Notification> notifications = notificationRepository
                .findByUserIdOrderByCreatedAtDescIdDesc(userId("fijo@test.com"));
        assertThat(notifications).singleElement().satisfies(n -> {
            assertThat(n.getChannel()).isEqualTo(Notification.Channel.SMS);
            assertThat(n.getType()).isEqualTo(Notification.NotificationType.TICKET_PURCHASED);
        });
        assertThat(notificationRepository.findByUserIdOrderByCreatedAtDescIdDesc(userId("clerk@test.com"))).isEmpty();
    }

    // Historia: como pasajero quiero notificaciones de cambios de andén
    @Test
    void platformChange_shouldNotifyAllSoldPassengersOnce() throws Exception {
        String ana = registerAndLogin("ana@test.com", "3001111111");
        String luis = registerAndLogin("luis@test.com", "3002222222");
        String sofia = registerAndLogin("sofia@test.com", "3003333333");
        purchase(ana, userId("ana@test.com"), 1, stopA, stopC).andExpect(status().isCreated());
        purchase(ana, userId("ana@test.com"), 2, stopA, stopC).andExpect(status().isCreated());
        purchase(luis, userId("luis@test.com"), 3, stopB, stopC).andExpect(status().isCreated());
        Long cancelled = json(purchase(sofia, userId("sofia@test.com"), 4, stopA, stopC)).get("id").asLong();
        mvc.perform(post("/api/v1/tickets/{id}/cancel", cancelled).header("Authorization", bearer(sofia)))
                .andExpect(status().isOk());
        String dispatcherToken = staff("disp@test.com", User.Role.DISPATCHER);

        changePlatform(dispatcherToken, "a3")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.platform").value("A3"))
                .andExpect(jsonPath("$.previousPlatform").doesNotExist())
                .andExpect(jsonPath("$.changed").value(true));
        changePlatform(dispatcherToken, "B7")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.previousPlatform").value("A3"));
        // Repetir el mismo andén no vuelve a notificar
        changePlatform(dispatcherToken, "B7")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(false));

        flushAndClear();
        assertThat(tripRepository.findById(trip.getId()).orElseThrow().getPlatform()).isEqualTo("B7");
        List<Notification> platformNotifications = notificationRepository
                .findByTripIdAndType(trip.getId(), Notification.NotificationType.PLATFORM_CHANGED);
        // Dos cambios efectivos x dos pasajeros con ticket SOLD (Ana tiene dos tickets pero recibe un aviso)
        assertThat(platformNotifications).hasSize(4)
                .extracting(n -> n.getUser().getEmail())
                .containsOnly("ana@test.com", "luis@test.com");

        mvc.perform(get("/api/v1/notifications/me").header("Authorization", bearer(luis)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("PLATFORM_CHANGED"))
                .andExpect(jsonPath("$[0].message").value(containsString("andén B7")))
                .andExpect(jsonPath("$[0].message").value(containsString("antes: A3")))
                .andExpect(jsonPath("$[1].type").value("PLATFORM_CHANGED"))
                .andExpect(jsonPath("$[2].type").value("TICKET_PURCHASED"));
        // Quien canceló su ticket no recibe el aviso de andén
        mvc.perform(get("/api/v1/notifications/me").header("Authorization", bearer(sofia)))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].type").value("TICKET_PURCHASED"));

        // El ADMIN ve todas las notificaciones del viaje
        String adminToken = staff("admin@test.com", User.Role.ADMIN);
        mvc.perform(get("/api/v1/admin/notifications").param("tripId", trip.getId().toString())
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(8));
    }

    @Test
    void platformChange_shouldBeOnlyForDispatcherAndActiveTrips() throws Exception {
        String passengerToken = registerAndLogin("pax@test.com", "3001234567");
        String dispatcherToken = staff("disp@test.com", User.Role.DISPATCHER);

        changePlatform(passengerToken, "A1").andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/trips/{id}/platform", trip.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new PlatformUpdateRequest("A1"))))
                .andExpect(status().isUnauthorized());
        mvc.perform(put("/api/v1/trips/{id}/platform", 999999L)
                        .header("Authorization", bearer(dispatcherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new PlatformUpdateRequest("A1"))))
                .andExpect(status().isNotFound());

        Trip departed = tripRepository.findById(trip.getId()).orElseThrow();
        departed.setStatus(Trip.TripStatus.DEPARTED);
        tripRepository.save(departed);
        flushAndClear();
        changePlatform(dispatcherToken, "A1")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("DEPARTED")));
    }

    // Cancelar el viaje avisa a los pasajeros con ticket vendido
    @Test
    void tripCancellation_shouldNotifyTripCancelledToSoldPassengers() throws Exception {
        String ana = registerAndLogin("ana@test.com", "3001111111");
        String luis = registerAndLogin("luis@test.com", "6012222222");
        purchase(ana, userId("ana@test.com"), 1, stopA, stopC).andExpect(status().isCreated());
        purchase(luis, userId("luis@test.com"), 2, stopA, stopB).andExpect(status().isCreated());
        String adminToken = staff("admin@test.com", User.Role.ADMIN);

        mvc.perform(delete("/api/v1/trips/{id}", trip.getId()).header("Authorization", bearer(adminToken)))
                .andExpect(status().is2xxSuccessful());

        flushAndClear();
        assertThat(ticketRepository.findByTripId(trip.getId()))
                .allSatisfy(t -> assertThat(t.getStatus()).isEqualTo(Ticket.TicketStatus.CANCELLED));
        List<Notification> cancelledNotifications = notificationRepository
                .findByTripIdAndType(trip.getId(), Notification.NotificationType.TRIP_CANCELLED);
        assertThat(cancelledNotifications)
                .extracting(n -> n.getUser().getEmail(), Notification::getChannel)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("ana@test.com", Notification.Channel.WHATSAPP),
                        org.assertj.core.groups.Tuple.tuple("luis@test.com", Notification.Channel.SMS));

        mvc.perform(get("/api/v1/notifications/me").header("Authorization", bearer(luis)))
                .andExpect(jsonPath("$[0].type").value("TRIP_CANCELLED"))
                .andExpect(jsonPath("$[0].message").value(containsString("cancelado")));
    }

    // Aviso de llegada próxima: solo viajes DEPARTED que llegan en 15 min, una única vez
    @Test
    void arrivalSoonScheduler_shouldNotifyOnceAndMarkTrip() throws Exception {
        String ana = registerAndLogin("ana@test.com", "3001111111");
        purchase(ana, userId("ana@test.com"), 1, stopA, stopC).andExpect(status().isCreated());
        Long noShow = json(purchase(ana, userId("ana@test.com"), 2, stopA, stopC)).get("id").asLong();

        // Otro viaje en curso cuya llegada aún está lejos
        Trip farTrip = tripRepository.save(Trip.builder()
                .route(trip.getRoute())
                .bus(trip.getBus())
                .tripDate(LocalDate.now())
                .departureTime(LocalDateTime.now().minusHours(1))
                .arrivalEta(LocalDateTime.now().plusMinutes(40))
                .status(Trip.TripStatus.DEPARTED)
                .build());

        Trip departed = tripRepository.findById(trip.getId()).orElseThrow();
        departed.setStatus(Trip.TripStatus.DEPARTED);
        departed.setArrivalEta(LocalDateTime.now().plusMinutes(10));
        tripRepository.save(departed);
        Ticket ticket = ticketRepository.findById(noShow).orElseThrow();
        ticket.setStatus(Ticket.TicketStatus.NO_SHOW);
        ticketRepository.save(ticket);
        flushAndClear();

        notificationScheduler.notifyUpcomingArrivals();
        flushAndClear();

        assertThat(tripRepository.findById(trip.getId()).orElseThrow().getArrivalNotified()).isTrue();
        assertThat(tripRepository.findById(farTrip.getId()).orElseThrow().getArrivalNotified()).isFalse();
        assertThat(notificationRepository.findByTripIdAndType(trip.getId(), Notification.NotificationType.ARRIVAL_SOON))
                .singleElement()
                .satisfies(n -> assertThat(n.getMessage()).contains("Barranquilla"));

        // Una segunda ejecución no repite el aviso
        notificationScheduler.notifyUpcomingArrivals();
        flushAndClear();
        assertThat(notificationRepository.findByTripIdAndType(trip.getId(), Notification.NotificationType.ARRIVAL_SOON))
                .hasSize(1);
        assertThat(notificationRepository.findByTripIdAndType(farTrip.getId(), Notification.NotificationType.ARRIVAL_SOON))
                .isEmpty();

        mvc.perform(get("/api/v1/notifications/me").header("Authorization", bearer(ana)))
                .andExpect(jsonPath("$[0].type").value("ARRIVAL_SOON"));
    }

    // ---------- Helpers ----------

    private ResultActions purchase(String token, Long passengerId, int seat, Stop from, Stop to) throws Exception {
        TicketCreateRequest request = new TicketCreateRequest(
                trip.getId(), passengerId, seat,
                from.getId(), from.getName(), from.getOrder(),
                to.getId(), to.getName(), to.getOrder(),
                new BigDecimal("50000"), Ticket.PaymentMethod.CASH, null, null);

        return mvc.perform(post("/api/v1/trips/{tripId}/tickets", trip.getId())
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(request)));
    }

    private ResultActions changePlatform(String token, String platform) throws Exception {
        return mvc.perform(put("/api/v1/trips/{id}/platform", trip.getId())
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(new PlatformUpdateRequest(platform))));
    }

    private JsonNode json(ResultActions purchase) throws Exception {
        return om.readTree(purchase.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private String staff(String email, User.Role role) throws Exception {
        createUser(new RegisterRequest("Staff " + role, email, "3009999999", "secreto1", role));
        return login(email, "secreto1");
    }

    private String registerAndLogin(String email, String phone) throws Exception {
        mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(
                                new RegisterRequest("Pasajero", email, phone, "secreto1", User.Role.PASSENGER))))
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
