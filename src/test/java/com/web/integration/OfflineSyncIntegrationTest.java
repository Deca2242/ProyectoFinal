package com.web.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.dto.auth.Login.LoginRequest;
import com.web.dto.auth.Login.LoginResponse;
import com.web.dto.auth.Login.RegisterRequest;
import com.web.dto.sync.BoardingSyncRequest;
import com.web.dto.sync.OfflineBoarding;
import com.web.dto.sync.OfflineTicketSale;
import com.web.dto.sync.SyncBatchResponse;
import com.web.dto.sync.SyncItemResult;
import com.web.dto.sync.TicketSyncRequest;
import com.web.entity.Bus;
import com.web.entity.Route;
import com.web.entity.Stop;
import com.web.entity.Ticket;
import com.web.entity.Trip;
import com.web.entity.User;
import com.web.integration.userstories.BaseIntegrationTest;
import com.web.repository.BusRepository;
import com.web.repository.RouteRepository;
import com.web.repository.StopRepository;
import com.web.repository.TicketRepository;
import com.web.repository.TripRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Caso de uso 5 / historias CLERK y DRIVER: ventas y abordajes offline (pendingSync) que se reconcilian
// al volver la conexión sin duplicar sillas. Sin transacción de test: cada venta se confirma en su propia
// transacción (REQUIRES_NEW), así que los datos deben estar confirmados y se limpian al final
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OfflineSyncIntegrationTest extends BaseIntegrationTest {

    private static final String PASSWORD = "secreto123";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper om;
    @Autowired
    private JdbcTemplate jdbc;
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

    private final List<Long> userIds = new ArrayList<>();
    private final List<Long> tripIds = new ArrayList<>();
    private Route route;
    private Bus bus;
    private Stop stopA;
    private Stop stopB;
    private Stop stopC;
    private Trip trip;

    private User clerk1;
    private User clerk2;
    private User driver;
    private String clerk1Token;
    private String clerk2Token;
    private String driverToken;
    private String adminToken;
    private final List<Long> passengerIds = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        String suffix = String.valueOf(System.nanoTime());
        route = routeRepository.save(Route.builder()
                .code("OFF-" + suffix.substring(suffix.length() - 10))
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
                .plate("OF" + suffix.substring(suffix.length() - 8))
                .capacity(40)
                .amenities(new HashMap<>())
                .status(Bus.BusStatus.ACTIVE)
                .build());
        LocalDate date = LocalDate.now().plusDays(1);
        trip = saveTrip(date.atTime(12, 0), Trip.TripStatus.SCHEDULED);

        clerk1 = staff("Taquilla Uno", "clerk1-" + suffix + "@test.com", User.Role.CLERK);
        clerk2 = staff("Taquilla Dos", "clerk2-" + suffix + "@test.com", User.Role.CLERK);
        driver = staff("Conductor", "driver-" + suffix + "@test.com", User.Role.DRIVER);
        User admin = staff("Admin", "admin-" + suffix + "@test.com", User.Role.ADMIN);
        clerk1Token = login(clerk1.getEmail());
        clerk2Token = login(clerk2.getEmail());
        driverToken = login(driver.getEmail());
        adminToken = login(admin.getEmail());

        // Pasajeros por el registro público
        for (int i = 1; i <= 3; i++) {
            String email = "pasajero" + i + "-" + suffix + "@test.com";
            mvc.perform(post("/api/v1/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(new RegisterRequest(
                                    "Pasajero " + i, email, "300000000" + i, PASSWORD, null))))
                    .andExpect(status().isCreated());
            LoginResponse loginResponse = loginResponse(email);
            passengerIds.add(loginResponse.user().id());
            userIds.add(loginResponse.user().id());
        }
    }

    @AfterEach
    void cleanUp() {
        for (Long userId : userIds) {
            jdbc.update("DELETE FROM sync_batches WHERE user_id = ?", userId);
        }
        for (Long tripId : tripIds) {
            jdbc.update("DELETE FROM baggage WHERE ticket_id IN (SELECT id FROM tickets WHERE trip_id = ?)", tripId);
            jdbc.update("DELETE FROM seat_holds WHERE trip_id = ?", tripId);
            jdbc.update("DELETE FROM tickets WHERE trip_id = ?", tripId);
            jdbc.update("DELETE FROM assignments WHERE trip_id = ?", tripId);
            jdbc.update("DELETE FROM trips WHERE id = ?", tripId);
        }
        jdbc.update("DELETE FROM stops WHERE route_id = ?", route.getId());
        jdbc.update("DELETE FROM routes WHERE id = ?", route.getId());
        jdbc.update("DELETE FROM buses WHERE id = ?", bus.getId());
        for (Long userId : userIds) {
            jdbc.update("DELETE FROM users WHERE id = ?", userId);
        }
    }

    // ---------- Utilidades ----------

    private Trip saveTrip(LocalDateTime departure, Trip.TripStatus status) {
        Trip saved = tripRepository.save(Trip.builder()
                .route(route)
                .bus(bus)
                .tripDate(departure.toLocalDate())
                .departureTime(departure)
                .arrivalEta(departure.plusHours(2))
                .status(status)
                .build());
        tripIds.add(saved.getId());
        return saved;
    }

    private User staff(String name, String email, User.Role role) {
        User user = createUser(new RegisterRequest(name, email, "3100000000", PASSWORD, role));
        userIds.add(user.getId());
        return user;
    }

    private LoginResponse loginResponse(String email) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new LoginRequest(email, PASSWORD))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return om.readValue(body, LoginResponse.class);
    }

    private String login(String email) throws Exception {
        return loginResponse(email).token();
    }

    private OfflineTicketSale sale(Long tripId, int passengerIndex, int seat, Stop from, Stop to, LocalDateTime soldAt) {
        return new OfflineTicketSale(UUID.randomUUID().toString(), tripId, passengerIds.get(passengerIndex), seat,
                from.getId(), to.getId(), Ticket.PaymentMethod.CASH, null, soldAt, null);
    }

    private SyncBatchResponse syncTickets(String token, TicketSyncRequest request) throws Exception {
        String body = mvc.perform(post("/api/v1/sync/tickets")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return om.readValue(body, SyncBatchResponse.class);
    }

    private SyncBatchResponse syncBoardings(String token, BoardingSyncRequest request) throws Exception {
        String body = mvc.perform(post("/api/v1/sync/boardings")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return om.readValue(body, SyncBatchResponse.class);
    }

    private JsonNode conflicts(String token, String deviceId) throws Exception {
        String body = mvc.perform(get("/api/v1/sync/conflicts")
                        .param("deviceId", deviceId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return om.readTree(body);
    }

    private int ticketCount(Long tripId) {
        return ticketRepository.findByTripId(tripId).size();
    }

    // ---------- Ventas offline ----------

    @Test
    void syncTickets_batchOfThree_shouldSyncAndKeepRealSaleTime_andResendShouldBeAllDuplicates() throws Exception {
        // Given: la taquilla vendió 3 sillas sin red hace una hora
        LocalDateTime soldAt = LocalDateTime.now().minusHours(1).truncatedTo(ChronoUnit.SECONDS);
        TicketSyncRequest batch = new TicketSyncRequest("tablet-taquilla-1", List.of(
                sale(trip.getId(), 0, 1, stopA, stopC, soldAt),
                sale(trip.getId(), 1, 2, stopA, stopB, soldAt.plusMinutes(1)),
                sale(trip.getId(), 2, 3, stopB, stopC, soldAt.plusMinutes(2))));

        // When
        SyncBatchResponse first = syncTickets(clerk1Token, batch);

        // Then
        assertThat(first.batchId()).isNotNull();
        assertThat(first.total()).isEqualTo(3);
        assertThat(first.synced()).isEqualTo(3);
        assertThat(first.duplicates()).isZero();
        assertThat(first.conflicts()).isZero();
        assertThat(first.results()).allSatisfy(r -> {
            assertThat(r.status()).isEqualTo(SyncItemResult.Status.SYNCED);
            assertThat(r.ticketId()).isNotNull();
            assertThat(r.qrCode()).isNotBlank();
        });
        assertThat(ticketCount(trip.getId())).isEqualTo(3);

        // purchasedAt = soldAt del dispositivo, con id offline, fecha de sincronización, canal taquilla y vendedor
        for (int i = 0; i < 3; i++) {
            Ticket ticket = ticketRepository.findById(first.results().get(i).ticketId()).orElseThrow();
            assertThat(ticket.getPurchasedAt()).isEqualTo(batch.sales().get(i).soldAt());
            assertThat(ticket.getOfflineClientId()).isEqualTo(batch.sales().get(i).offlineClientId());
            assertThat(ticket.getSyncedAt()).isAfter(ticket.getPurchasedAt());
            assertThat(ticket.getChannel()).isEqualTo(Ticket.SalesChannel.BOX_OFFICE);
            assertThat(ticket.getStatus()).isEqualTo(Ticket.TicketStatus.SOLD);
            Long soldBy = jdbc.queryForObject("SELECT sold_by_id FROM tickets WHERE id = ?", Long.class, ticket.getId());
            assertThat(soldBy).isEqualTo(clerk1.getId());
        }

        // When: el dispositivo no recibió la respuesta y reenvía el mismo lote
        SyncBatchResponse resend = syncTickets(clerk1Token, batch);

        // Then: todo DUPLICATE con los mismos tickets y sin sillas nuevas
        assertThat(resend.duplicates()).isEqualTo(3);
        assertThat(resend.synced()).isZero();
        assertThat(resend.conflicts()).isZero();
        assertThat(resend.results()).extracting(SyncItemResult::ticketId)
                .containsExactlyElementsOf(first.results().stream().map(SyncItemResult::ticketId).toList());
        assertThat(resend.results()).extracting(SyncItemResult::qrCode)
                .containsExactlyElementsOf(first.results().stream().map(SyncItemResult::qrCode).toList());
        assertThat(ticketCount(trip.getId())).isEqualTo(3);

        // Ambos lotes quedan auditados
        List<Integer> duplicatesPerBatch = jdbc.queryForList(
                "SELECT duplicates FROM sync_batches WHERE user_id = ? AND device_id = ? AND type = 'TICKETS' ORDER BY id",
                Integer.class, clerk1.getId(), "tablet-taquilla-1");
        assertThat(duplicatesPerBatch).containsExactly(0, 3);
    }

    @Test
    void syncTickets_twoDevicesSoldSameSeatOffline_shouldSyncOneAndRegisterConflictForTheOther() throws Exception {
        // Given: dos taquillas sin red vendieron la silla 5 en tramos que se solapan
        LocalDateTime soldAt = LocalDateTime.now().minusMinutes(40).truncatedTo(ChronoUnit.SECONDS);
        OfflineTicketSale saleDeviceA = sale(trip.getId(), 0, 5, stopA, stopC, soldAt);
        OfflineTicketSale saleDeviceB = sale(trip.getId(), 1, 5, stopA, stopB, soldAt.plusMinutes(3));
        OfflineTicketSale otherSeatDeviceB = sale(trip.getId(), 2, 6, stopA, stopC, soldAt.plusMinutes(4));

        // When: el dispositivo A recupera señal primero
        SyncBatchResponse deviceA = syncTickets(clerk1Token, new TicketSyncRequest("tablet-A", List.of(saleDeviceA)));
        SyncBatchResponse deviceB = syncTickets(clerk2Token,
                new TicketSyncRequest("tablet-B", List.of(saleDeviceB, otherSeatDeviceB)));

        // Then: A gana la silla; en B la silla 5 queda en conflicto pero la silla 6 sí se sincroniza
        assertThat(deviceA.results().get(0).status()).isEqualTo(SyncItemResult.Status.SYNCED);
        SyncItemResult conflict = deviceB.results().get(0);
        assertThat(conflict.status()).isEqualTo(SyncItemResult.Status.CONFLICT);
        assertThat(conflict.code()).isEqualTo("SEAT_NOT_AVAILABLE");
        assertThat(conflict.offlineClientId()).isEqualTo(saleDeviceB.offlineClientId());
        assertThat(conflict.ticketId()).isNull();
        assertThat(deviceB.results().get(1).status()).isEqualTo(SyncItemResult.Status.SYNCED);
        assertThat(deviceB.synced()).isEqualTo(1);
        assertThat(deviceB.conflicts()).isEqualTo(1);

        assertThat(ticketRepository.findByTripIdAndSeatNumber(trip.getId(), 5))
                .filteredOn(t -> t.getStatus() == Ticket.TicketStatus.SOLD)
                .singleElement()
                .satisfies(t -> assertThat(t.getOfflineClientId()).isEqualTo(saleDeviceA.offlineClientId()));
        assertThat(ticketRepository.findByOfflineClientId(saleDeviceB.offlineClientId())).isEmpty();

        // La taquilla B ve su conflicto con el motivo y la venta original
        JsonNode conflictsB = conflicts(clerk2Token, "tablet-B");
        assertThat(conflictsB).hasSize(1);
        assertThat(conflictsB.get(0).get("offlineClientId").asText()).isEqualTo(saleDeviceB.offlineClientId());
        assertThat(conflictsB.get(0).get("code").asText()).isEqualTo("SEAT_NOT_AVAILABLE");
        assertThat(conflictsB.get(0).get("deviceId").asText()).isEqualTo("tablet-B");
        assertThat(conflictsB.get(0).get("type").asText()).isEqualTo("TICKETS");
        assertThat(conflictsB.get(0).get("batchId").asLong()).isEqualTo(deviceB.batchId());
        assertThat(conflictsB.get(0).get("payload").asText()).contains(saleDeviceB.offlineClientId());

        // La taquilla A no ve conflictos ajenos; ADMIN sí
        assertThat(conflicts(clerk1Token, "tablet-B")).isEmpty();
        assertThat(conflicts(adminToken, "tablet-B")).hasSize(1);

        // Reenviar el lote de B no vende la silla 6 otra vez y la 5 sigue en conflicto
        SyncBatchResponse resendB = syncTickets(clerk2Token,
                new TicketSyncRequest("tablet-B", List.of(saleDeviceB, otherSeatDeviceB)));
        assertThat(resendB.results()).extracting(SyncItemResult::status)
                .containsExactly(SyncItemResult.Status.CONFLICT, SyncItemResult.Status.DUPLICATE);
        assertThat(ticketCount(trip.getId())).isEqualTo(2);
    }

    @Test
    void syncTickets_saleBeforeDepartureSyncedAfterTripLeft_shouldBeAccepted_butSaleAfterDepartureConflicts() throws Exception {
        // Given: viaje que ya salió hace 30 minutos
        LocalDateTime departure = LocalDateTime.now().minusMinutes(30).truncatedTo(ChronoUnit.SECONDS);
        Trip departed = saveTrip(departure, Trip.TripStatus.DEPARTED);
        OfflineTicketSale before = sale(departed.getId(), 0, 1, stopA, stopC, departure.minusMinutes(20));
        OfflineTicketSale after = sale(departed.getId(), 1, 2, stopA, stopC, departure.plusMinutes(5));

        // When
        SyncBatchResponse response = syncTickets(clerk1Token,
                new TicketSyncRequest("tablet-1", List.of(before, after)));

        // Then
        assertThat(response.results().get(0).status()).isEqualTo(SyncItemResult.Status.SYNCED);
        assertThat(response.results().get(1).status()).isEqualTo(SyncItemResult.Status.CONFLICT);
        assertThat(response.results().get(1).code()).isEqualTo("SOLD_AFTER_DEPARTURE");
        assertThat(ticketRepository.findById(response.results().get(0).ticketId()).orElseThrow().getPurchasedAt())
                .isEqualTo(before.soldAt());
    }

    @Test
    void syncTickets_asPassenger_shouldBeForbidden() throws Exception {
        String passengerToken = login(jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class,
                passengerIds.get(0)));
        mvc.perform(post("/api/v1/sync/tickets")
                        .header("Authorization", "Bearer " + passengerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new TicketSyncRequest("x",
                                List.of(sale(trip.getId(), 0, 1, stopA, stopC, LocalDateTime.now()))))))
                .andExpect(status().isForbidden());
    }

    // ---------- Abordajes offline ----------

    @Test
    void syncBoardings_shouldKeepDeviceTimeAndBeIdempotent() throws Exception {
        // Given: un tiquete vendido, el conductor asignado y el abordaje abierto
        SyncBatchResponse sold = syncTickets(clerk1Token, new TicketSyncRequest("tablet-1", List.of(
                sale(trip.getId(), 0, 8, stopA, stopC, LocalDateTime.now().minusHours(2)))));
        String qr = sold.results().get(0).qrCode();
        Long ticketId = sold.results().get(0).ticketId();
        jdbc.update("INSERT INTO assignments (trip_id, driver_id, checklist_ok, soat_valid, revision_valid, assigned_at) "
                + "VALUES (?, ?, true, true, true, now())", trip.getId(), driver.getId());
        jdbc.update("UPDATE trips SET status = 'BOARDING' WHERE id = ?", trip.getId());

        LocalDateTime boardedAt = LocalDateTime.now().minusMinutes(10).truncatedTo(ChronoUnit.SECONDS);
        BoardingSyncRequest request = new BoardingSyncRequest("phone-conductor", List.of(
                new OfflineBoarding(qr, boardedAt),
                new OfflineBoarding("QR-INEXISTENTE", boardedAt)));

        // When
        SyncBatchResponse first = syncBoardings(driverToken, request);

        // Then
        assertThat(first.results().get(0).status()).isEqualTo(SyncItemResult.Status.SYNCED);
        assertThat(first.results().get(0).ticketId()).isEqualTo(ticketId);
        assertThat(first.results().get(1).status()).isEqualTo(SyncItemResult.Status.CONFLICT);
        assertThat(first.results().get(1).code()).isEqualTo("RESOURCE_NOT_FOUND");
        assertThat(ticketRepository.findById(ticketId).orElseThrow().getBoardedAt()).isEqualTo(boardedAt);

        // When: reenvío del mismo lote
        SyncBatchResponse resend = syncBoardings(driverToken, request);

        // Then: el abordaje ya registrado es DUPLICATE y conserva la hora original
        assertThat(resend.results().get(0).status()).isEqualTo(SyncItemResult.Status.DUPLICATE);
        assertThat(resend.results().get(0).ticketId()).isEqualTo(ticketId);
        assertThat(resend.duplicates()).isEqualTo(1);
        assertThat(ticketRepository.findById(ticketId).orElseThrow().getBoardedAt()).isEqualTo(boardedAt);

        // El QR desconocido queda como conflicto del conductor
        JsonNode driverConflicts = conflicts(driverToken, "phone-conductor");
        assertThat(driverConflicts).hasSize(2);
        assertThat(driverConflicts.get(0).get("type").asText()).isEqualTo("BOARDINGS");
        assertThat(driverConflicts.get(0).get("offlineClientId").asText()).isEqualTo("QR-INEXISTENTE");
    }

    @Test
    void syncBoardings_byUnassignedDriver_shouldConflict() throws Exception {
        // Given: tiquete vendido y abordaje abierto, pero el conductor no está asignado al viaje
        SyncBatchResponse sold = syncTickets(clerk1Token, new TicketSyncRequest("tablet-1", List.of(
                sale(trip.getId(), 0, 9, stopA, stopC, LocalDateTime.now().minusHours(2)))));
        jdbc.update("UPDATE trips SET status = 'BOARDING' WHERE id = ?", trip.getId());

        // When
        SyncBatchResponse response = syncBoardings(driverToken, new BoardingSyncRequest("phone-conductor", List.of(
                new OfflineBoarding(sold.results().get(0).qrCode(), LocalDateTime.now().minusMinutes(1)))));

        // Then
        assertThat(response.results().get(0).status()).isEqualTo(SyncItemResult.Status.CONFLICT);
        assertThat(response.results().get(0).code()).isEqualTo("DRIVER_NOT_ASSIGNED");
        assertThat(ticketRepository.findById(sold.results().get(0).ticketId()).orElseThrow().getBoardedAt()).isNull();
    }
}
