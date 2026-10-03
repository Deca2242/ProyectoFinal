package com.web.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.dto.auth.Login.LoginRequest;
import com.web.dto.auth.Login.RegisterRequest;
import com.web.dto.payment.CashCloseRequest;
import com.web.dto.payment.PaymentConfirmRequest;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Pagos: estado de pago del ticket, confirmación QR/transferencia por taquilla, comprobante digital
// y cierre de caja persistido (HTTP + JWT + BD)
class PaymentFlowIntegrationTest extends BaseIntegrationTest {

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
    private PaymentRepository paymentRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    private Stop stopA;
    private Stop stopC;
    private Trip trip;

    @BeforeEach
    void setUp() {
        Route route = routeRepository.save(Route.builder()
                .code("PAY-" + System.nanoTime())
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
        Bus bus = busRepository.save(Bus.builder()
                .plate("PAY" + (System.nanoTime() % 100000))
                .capacity(40)
                .amenities(new HashMap<>())
                .status(Bus.BusStatus.ACTIVE)
                .build());
        // Viaje en abordaje: admite ventas y validación de QR
        LocalDate date = LocalDate.now().plusDays(3);
        trip = tripRepository.save(Trip.builder()
                .route(route)
                .bus(bus)
                .tripDate(date)
                .departureTime(date.atTime(12, 0))
                .arrivalEta(date.atTime(14, 0))
                .status(Trip.TripStatus.BOARDING)
                .build());
        entityManager.flush();
        entityManager.clear();
    }

    // ---------- Compra por la app: PENDING hasta que la taquilla confirma ----------

    @Test
    void appPurchase_isPendingUntilClerkConfirmsQr_thenHasReceiptAndCanBoard() throws Exception {
        String pax = registerAndLogin("pax@test.com");
        String clerk = staff("clerk@test.com", User.Role.CLERK);
        String dispatcher = staff("disp@test.com", User.Role.DISPATCHER);

        JsonNode ticket = json(purchase(pax, userId("pax@test.com"), 1, Ticket.PaymentMethod.QR));
        Long ticketId = ticket.get("id").asLong();
        String qr = ticket.get("qrCode").asText();
        BigDecimal price = new BigDecimal(ticket.get("price").asText());
        assertThat(ticketRepository.findById(ticketId).orElseThrow().getPaymentStatus())
                .isEqualTo(Ticket.PaymentStatus.PENDING);
        assertThat(paymentRepository.findByTicketId(ticketId)).isEmpty();

        // Sin pagar no aborda ni tiene comprobante
        board(dispatcher, qr).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(startsWith("El ticket tiene el pago pendiente")));
        receipt(pax, ticketId).andExpect(status().isNotFound());

        // Un QR sin referencia o con otro monto no se acepta
        confirm(clerk, new PaymentConfirmRequest(ticketId, Ticket.PaymentMethod.QR, null, null, null))
                .andExpect(status().isBadRequest());
        confirm(clerk, new PaymentConfirmRequest(ticketId, Ticket.PaymentMethod.QR, "NEQUI-778", price.add(BigDecimal.ONE), null))
                .andExpect(status().isBadRequest());

        // La taquilla confirma el pago QR con su referencia
        confirm(clerk, new PaymentConfirmRequest(ticketId, Ticket.PaymentMethod.QR, "NEQUI-778", price, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receiptNumber").value(startsWith("RCP-")))
                .andExpect(jsonPath("$.ticketId").value(ticketId))
                .andExpect(jsonPath("$.qrCode").value(qr))
                .andExpect(jsonPath("$.paymentStatus").value("PAID"))
                .andExpect(jsonPath("$.confirmedBy").value("Staff CLERK"));

        // Confirmar de nuevo (o cambiar el método) ya no es posible
        confirm(clerk, new PaymentConfirmRequest(ticketId, Ticket.PaymentMethod.CARD, "TX-2", null, null))
                .andExpect(status().isConflict());

        // El pasajero ve su comprobante y ya puede abordar
        receipt(pax, ticketId).andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentMethod").value("QR"))
                .andExpect(jsonPath("$.transactionReference").value("NEQUI-778"));
        board(dispatcher, qr).andExpect(status().isOk());
    }

    @Test
    void receipt_isOnlyVisibleToOwnerPassengerAndStaff() throws Exception {
        String clerk = staff("clerk@test.com", User.Role.CLERK);
        String driver = staff("driver@test.com", User.Role.DRIVER);
        String admin = staff("admin@test.com", User.Role.ADMIN);
        registerAndLogin("owner@test.com");
        String other = registerAndLogin("other@test.com");
        Long ticketId = json(purchase(clerk, userId("owner@test.com"), 2, Ticket.PaymentMethod.CASH)).get("id").asLong();

        receipt(other, ticketId).andExpect(status().isForbidden());
        receipt(driver, ticketId).andExpect(status().isForbidden());
        receipt(admin, ticketId).andExpect(status().isOk());
    }

    @Test
    void pendingTicketCancelled_hasNoRefund() throws Exception {
        String pax = registerAndLogin("pax@test.com");
        Long ticketId = json(purchase(pax, userId("pax@test.com"), 3, Ticket.PaymentMethod.TRANSFER)).get("id").asLong();

        mvc.perform(post("/api/v1/tickets/{id}/cancel", ticketId).header("Authorization", bearer(pax)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refundAmount").value(0));
    }

    // ---------- Venta en taquilla: PAID con comprobante ----------

    @Test
    void counterSale_isPaidWithReceipt() throws Exception {
        String clerk = staff("clerk@test.com", User.Role.CLERK);
        registerAndLogin("buyer@test.com");

        JsonNode ticket = json(purchase(clerk, userId("buyer@test.com"), 4, Ticket.PaymentMethod.CASH));
        Long ticketId = ticket.get("id").asLong();

        Ticket saved = ticketRepository.findById(ticketId).orElseThrow();
        assertThat(saved.getPaymentStatus()).isEqualTo(Ticket.PaymentStatus.PAID);
        assertThat(saved.getPaidAt()).isNotNull();

        receipt(clerk, ticketId).andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentMethod").value("CASH"))
                .andExpect(jsonPath("$.amount").value(ticket.get("price").asDouble()))
                .andExpect(jsonPath("$.confirmedBy").value("Staff CLERK"));

        // Un ticket ya cobrado no se "confirma" con otro método
        confirm(clerk, new PaymentConfirmRequest(ticketId, Ticket.PaymentMethod.CARD, "TX-9", null, null))
                .andExpect(status().isConflict());
        assertThat(ticketRepository.findById(ticketId).orElseThrow().getPaymentMethod())
                .isEqualTo(Ticket.PaymentMethod.CASH);
    }

    // ---------- Cierre de caja persistido ----------

    @Test
    void cashClose_isPersistedWithTotalsByMethod_andCannotBeRepeated() throws Exception {
        String clerk = staff("clerk@test.com", User.Role.CLERK);
        String pax = registerAndLogin("pax@test.com");
        Long paxId = userId("pax@test.com");

        // Venta en efectivo en taquilla, compra por la app confirmada por transferencia y otra que sigue pendiente
        BigDecimal cashPrice = price(json(purchase(clerk, paxId, 5, Ticket.PaymentMethod.CASH)));
        JsonNode transfer = json(purchase(pax, paxId, 6, Ticket.PaymentMethod.TRANSFER));
        confirm(clerk, new PaymentConfirmRequest(transfer.get("id").asLong(), Ticket.PaymentMethod.TRANSFER,
                "BANCO-55", null, "https://comprobantes/55.png")).andExpect(status().isOk());
        Long pending = json(purchase(pax, paxId, 7, Ticket.PaymentMethod.CASH)).get("id").asLong();

        JsonNode close = om.readTree(closeCash(clerk, new CashCloseRequest(LocalDate.now(), null, cashPrice, "Turno mañana"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        // Solo cuentan los tickets pagados; la transferencia no está en el efectivo
        assertThat(close.get("id").asLong()).isPositive();
        assertThat(new BigDecimal(close.get("expectedAmount").asText())).isEqualByComparingTo(cashPrice);
        assertThat(new BigDecimal(close.get("difference").asText())).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(new BigDecimal(close.get("totalsByMethod").get("CASH").asText())).isEqualByComparingTo(cashPrice);
        assertThat(new BigDecimal(close.get("totalsByMethod").get("TRANSFER").asText())).isEqualByComparingTo(price(transfer));
        assertThat(new BigDecimal(close.get("totalsByMethod").get("QR").asText())).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(close.get("ticketCount").asInt()).isEqualTo(2);
        assertThat(close.get("notes").asText()).isEqualTo("Turno mañana");

        // Segundo cierre del mismo día: 409
        closeCash(clerk, new CashCloseRequest(LocalDate.now(), null, BigDecimal.ZERO, null))
                .andExpect(status().isConflict());

        // Con la caja cerrada no se registran más pagos ese día: 422
        confirm(clerk, new PaymentConfirmRequest(pending, Ticket.PaymentMethod.CASH, null, null, null))
                .andExpect(status().isUnprocessableEntity());

        // El cajero ve su cierre guardado
        mvc.perform(get("/api/v1/cash/closes").param("date", LocalDate.now().toString())
                        .header("Authorization", bearer(clerk)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(close.get("id").asLong()));
    }

    @Test
    void cashCloses_adminListsEveryone_clerkOnlyOwn() throws Exception {
        String clerk1 = staff("clerk1@test.com", User.Role.CLERK);
        String clerk2 = staff("clerk2@test.com", User.Role.CLERK);
        String driver = staff("driver@test.com", User.Role.DRIVER);
        String admin = staff("admin@test.com", User.Role.ADMIN);
        String pax = registerAndLogin("pax@test.com");

        List<Long> ids = new ArrayList<>();
        for (String token : List.of(clerk1, clerk2, driver)) {
            ids.add(om.readTree(closeCash(token, new CashCloseRequest(LocalDate.now(), null, BigDecimal.ZERO, null))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString()).get("id").asLong());
        }

        JsonNode all = om.readTree(mvc.perform(get("/api/v1/cash/closes").param("date", LocalDate.now().toString())
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        List<Long> listed = new ArrayList<>();
        all.forEach(c -> listed.add(c.get("id").asLong()));
        assertThat(listed).containsAll(ids);

        mvc.perform(get("/api/v1/cash/closes").header("Authorization", bearer(clerk2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(ids.get(1)));

        mvc.perform(get("/api/v1/cash/closes").header("Authorization", bearer(pax)))
                .andExpect(status().isForbidden());
    }

    // ---------- Helpers ----------

    private ResultActions purchase(String token, Long passengerId, int seat, Ticket.PaymentMethod method) throws Exception {
        TicketCreateRequest request = new TicketCreateRequest(
                trip.getId(), passengerId, seat,
                stopA.getId(), null, null, stopC.getId(), null, null,
                new BigDecimal("50000"), method, null, null);
        return mvc.perform(post("/api/v1/trips/{tripId}/tickets", trip.getId())
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(request)));
    }

    private ResultActions confirm(String token, PaymentConfirmRequest request) throws Exception {
        return mvc.perform(post("/api/v1/payments/confirm")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(request)));
    }

    private ResultActions receipt(String token, Long ticketId) throws Exception {
        return mvc.perform(get("/api/v1/tickets/{id}/receipt", ticketId).header("Authorization", bearer(token)));
    }

    private ResultActions board(String token, String qr) throws Exception {
        return mvc.perform(post("/api/v1/tickets/qr/{qr}/board", qr).header("Authorization", bearer(token)));
    }

    private ResultActions closeCash(String token, CashCloseRequest request) throws Exception {
        return mvc.perform(post("/api/v1/cash/close")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(request)));
    }

    private JsonNode json(ResultActions purchase) throws Exception {
        return om.readTree(purchase.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private BigDecimal price(JsonNode ticket) {
        return new BigDecimal(ticket.get("price").asText());
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
}
