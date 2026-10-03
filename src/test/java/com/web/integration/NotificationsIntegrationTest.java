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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Notificaciones simuladas por WhatsApp/SMS de extremo a extremo: compra, cambio de andén, cancelación y llegada próxima.
// Las notificaciones se entregan cuando la operación de negocio confirma (cada una en su propia transacción),
// así que esta clase corre SIN transacción de prueba: cada petición confirma de verdad y @AfterEach borra lo creado.
// Las tareas programadas se apagan para que el aviso de llegada próxima solo lo dispare la prueba
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = "app.scheduling.enabled=false")
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

    // Spy: delega en el repositorio real salvo cuando una prueba fuerza un fallo al guardar
    @MockitoSpyBean
    private NotificationRepository notificationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private NotificationScheduler notificationScheduler;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ApplicationContext applicationContext;

    private final List<String> createdEmails = new ArrayList<>();
    private Route route;
    private Bus bus;
    private Stop stopA;
    private Stop stopB;
    private Stop stopC;
    private Trip trip;

    @BeforeEach
    void setUp() {
        route = routeRepository.save(Route.builder()
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

        bus = busRepository.save(Bus.builder()
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
    }

    // Todo lo de esta clase se confirmó: se borra para no afectar a las demás pruebas
    @AfterEach
    void cleanUp() {
        CommittedDataCleaner cleaner = new CommittedDataCleaner(jdbcTemplate);
        cleaner.delete("routes", List.of(route.getId()));
        cleaner.delete("buses", List.of(bus.getId()));
        List<Long> userIds = createdEmails.stream()
                .flatMap(email -> userRepository.findByEmail(email).stream())
                .map(User::getId)
                .toList();
        cleaner.delete("users", userIds);
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
                .andExpect(jsonPath("$[0].readAt").doesNotExist())
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

    // REQUIRES_NEW: si guardar la notificación falla, la compra se confirma igual (no queda rollback-only)
    @Test
    void purchase_whenSavingNotificationFails_shouldStillCommitTheSale() throws Exception {
        String token = registerAndLogin("fallo@test.com", "3001234567");
        doThrow(new DataIntegrityViolationException("violación simulada al guardar la notificación"))
                .when(notificationRepository).save(any(Notification.class));

        JsonNode ticket = json(purchase(token, userId("fallo@test.com"), 9, stopA, stopC));

        // El ticket quedó confirmado en la base de datos (se lee fuera de la transacción de la compra)
        Ticket saved = ticketRepository.findById(ticket.get("id").asLong()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(Ticket.TicketStatus.SOLD);
        assertThat(saved.getSeatNumber()).isEqualTo(9);
        mvc.perform(get("/api/v1/tickets/{id}", saved.getId()).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SOLD"));
        // Y no quedó notificación registrada
        mvc.perform(get("/api/v1/notifications/me").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // Una venta en taquilla notifica al pasajero, no al cajero; con teléfono fijo se usa SMS
    @Test
    void boxOfficeSale_shouldNotifyPassengerBySmsWhenLandline() throws Exception {
        registerAndLogin("fijo@test.com", "6015551234");
        String clerkToken = staff("clerk@test.com", User.Role.CLERK);

        purchase(clerkToken, userId("fijo@test.com"), 3, stopA, stopC).andExpect(status().isCreated());

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

        assertThat(tripRepository.findById(trip.getId()).orElseThrow().getPlatform()).isEqualTo("B7");
        // Dos cambios efectivos x dos pasajeros con ticket SOLD (Ana tiene dos tickets pero recibe un aviso)
        List<String> notifiedEmails = inTx(() -> notificationRepository
                .findByTripIdAndType(trip.getId(), Notification.NotificationType.PLATFORM_CHANGED).stream()
                .map(n -> n.getUser().getEmail())
                .toList());
        assertThat(notifiedEmails).hasSize(4).containsOnly("ana@test.com", "luis@test.com");

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

    // Marcar como leída: solo el destinatario; unreadOnly filtra las ya leídas
    @Test
    void markAsRead_shouldOnlyAllowOwner_andUnreadOnlyShouldHideReadNotifications() throws Exception {
        String ana = registerAndLogin("ana@test.com", "3001111111");
        String luis = registerAndLogin("luis@test.com", "3002222222");
        purchase(ana, userId("ana@test.com"), 1, stopA, stopC).andExpect(status().isCreated());
        purchase(ana, userId("ana@test.com"), 2, stopA, stopC).andExpect(status().isCreated());
        List<Notification> anaNotifications = notificationRepository
                .findByUserIdOrderByCreatedAtDescIdDesc(userId("ana@test.com"));
        assertThat(anaNotifications).hasSize(2);
        Long first = anaNotifications.get(1).getId();

        // Luis no puede marcar una notificación de Ana
        mvc.perform(patch("/api/v1/notifications/{id}/read", first).header("Authorization", bearer(luis)))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/v1/notifications/{id}/read", 999999L).header("Authorization", bearer(ana)))
                .andExpect(status().isNotFound());

        mvc.perform(patch("/api/v1/notifications/{id}/read", first).header("Authorization", bearer(ana)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(first))
                .andExpect(jsonPath("$.readAt").isNotEmpty());

        mvc.perform(get("/api/v1/notifications/me").param("unreadOnly", "true").header("Authorization", bearer(ana)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(anaNotifications.get(0).getId()));
        mvc.perform(get("/api/v1/notifications/me").header("Authorization", bearer(ana)))
                .andExpect(jsonPath("$.length()").value(2));
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
        // Cambiar el andén de un viaje que ya salió es un estado inválido para la operación (422)
        changePlatform(dispatcherToken, "A1")
                .andExpect(status().isUnprocessableEntity())
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

        assertThat(ticketRepository.findByTripId(trip.getId()))
                .allSatisfy(t -> assertThat(t.getStatus()).isEqualTo(Ticket.TicketStatus.CANCELLED));
        List<org.assertj.core.groups.Tuple> cancelled = inTx(() -> notificationRepository
                .findByTripIdAndType(trip.getId(), Notification.NotificationType.TRIP_CANCELLED).stream()
                .map(n -> org.assertj.core.groups.Tuple.tuple(n.getUser().getEmail(), n.getChannel()))
                .toList());
        assertThat(cancelled).containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple("ana@test.com", Notification.Channel.WHATSAPP),
                org.assertj.core.groups.Tuple.tuple("luis@test.com", Notification.Channel.SMS));

        mvc.perform(get("/api/v1/notifications/me").header("Authorization", bearer(luis)))
                .andExpect(jsonPath("$[0].type").value("TRIP_CANCELLED"))
                .andExpect(jsonPath("$[0].message").value(containsString("cancelado")));
    }

    // Aviso de llegada próxima: viajes DEPARTED que llegan en 15 min (o ya retrasados), una única vez
    @Test
    void arrivalSoonScheduler_shouldNotifyOnceAndMarkTrip() throws Exception {
        String ana = registerAndLogin("ana@test.com", "3001111111");
        purchase(ana, userId("ana@test.com"), 1, stopA, stopC).andExpect(status().isCreated());
        Long noShow = json(purchase(ana, userId("ana@test.com"), 2, stopA, stopC)).get("id").asLong();

        // Otro viaje en curso cuya llegada aún está lejos
        Trip farTrip = tripRepository.save(Trip.builder()
                .route(route)
                .bus(bus)
                .tripDate(LocalDate.now())
                .departureTime(LocalDateTime.now().minusHours(1))
                .arrivalEta(LocalDateTime.now().plusMinutes(40))
                .status(Trip.TripStatus.DEPARTED)
                .build());
        // Viaje retrasado: su ETA ya pasó y sigue en curso sin aviso
        Trip delayedTrip = tripRepository.save(Trip.builder()
                .route(route)
                .bus(bus)
                .tripDate(LocalDate.now())
                .departureTime(LocalDateTime.now().minusHours(3))
                .arrivalEta(LocalDateTime.now().minusMinutes(20))
                .status(Trip.TripStatus.DEPARTED)
                .build());

        Trip departed = tripRepository.findById(trip.getId()).orElseThrow();
        departed.setStatus(Trip.TripStatus.DEPARTED);
        departed.setArrivalEta(LocalDateTime.now().plusMinutes(10));
        tripRepository.save(departed);
        Ticket ticket = ticketRepository.findById(noShow).orElseThrow();
        ticket.setStatus(Ticket.TicketStatus.NO_SHOW);
        ticketRepository.save(ticket);

        notificationScheduler.notifyUpcomingArrivals();

        assertThat(tripRepository.findById(trip.getId()).orElseThrow().getArrivalNotified()).isTrue();
        assertThat(tripRepository.findById(delayedTrip.getId()).orElseThrow().getArrivalNotified()).isTrue();
        assertThat(tripRepository.findById(farTrip.getId()).orElseThrow().getArrivalNotified()).isFalse();
        assertThat(notificationRepository.findByTripIdAndType(trip.getId(), Notification.NotificationType.ARRIVAL_SOON))
                .singleElement()
                .satisfies(n -> assertThat(n.getMessage()).contains("Barranquilla"));

        // Una segunda ejecución no repite el aviso
        notificationScheduler.notifyUpcomingArrivals();
        assertThat(notificationRepository.findByTripIdAndType(trip.getId(), Notification.NotificationType.ARRIVAL_SOON))
                .hasSize(1);
        assertThat(notificationRepository.findByTripIdAndType(farTrip.getId(), Notification.NotificationType.ARRIVAL_SOON))
                .isEmpty();

        mvc.perform(get("/api/v1/notifications/me").header("Authorization", bearer(ana)))
                .andExpect(jsonPath("$[0].type").value("ARRIVAL_SOON"));
    }

    // Con app.scheduling.enabled=false no se registra ninguna tarea programada
    @Test
    void schedulingDisabled_shouldRegisterNoScheduledTasks() {
        long tasks = applicationContext.getBeanProvider(ScheduledTaskHolder.class).stream()
                .mapToLong(holder -> holder.getScheduledTasks().size())
                .sum();
        assertThat(tasks).isZero();
    }

    // ---------- Helpers ----------

    // Encomienda: el destinatario registrado recibe el aviso con el código de rastreo pero sin el OTP en claro
    @Test
    void parcelCreated_shouldNotifyRegisteredReceiverWithoutPlainOtp() throws Exception {
        String receiver = registerAndLogin("destinatario@test.com", "3005550001");
        String clerk = staff("clerk-enc@test.com", User.Role.CLERK);

        String body = mvc.perform(post("/api/v1/parcels")
                        .header("Authorization", bearer(clerk))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new com.web.dto.parcel.ParcelCreateRequest(
                                trip.getId(), "Remitente", "6015550000", "Destinatario", "3005550001",
                                stopA.getId(), null, stopC.getId(), null,
                                new BigDecimal("15000"), new BigDecimal("2.5"), "Caja con documentos"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode created = om.readTree(body);
        String code = created.get("code").asText();
        String otp = created.get("deliveryOtp").asText();

        mvc.perform(get("/api/v1/notifications/me").header("Authorization", bearer(receiver)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].type").value("PARCEL_CREATED"))
                .andExpect(jsonPath("$[0].channel").value("WHATSAPP"))
                .andExpect(jsonPath("$[0].status").value("SENT"))
                .andExpect(jsonPath("$[0].tripId").value(trip.getId()))
                .andExpect(jsonPath("$[0].message").value(containsString(code)))
                .andExpect(jsonPath("$[0].message").value(containsString("******")))
                .andExpect(jsonPath("$[0].message").value(org.hamcrest.Matchers.not(containsString(otp))));
    }

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
        createdEmails.add(email);
        createUser(new RegisterRequest("Staff " + role, email, "3009999999", "secreto1", role));
        return login(email, "secreto1");
    }

    private String registerAndLogin(String email, String phone) throws Exception {
        createdEmails.add(email);
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

    // Lecturas que navegan relaciones LAZY (sin transacción de prueba hace falta una propia)
    private <T> T inTx(Supplier<T> query) {
        return transactionTemplate.execute(status -> query.get());
    }
}
