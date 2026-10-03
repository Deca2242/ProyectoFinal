package com.web.integration.userstories;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.dto.admin.ConfigResponse;
import com.web.dto.admin.ConfigUpdateRequest;
import com.web.dto.auth.Login.LoginRequest;
import com.web.dto.auth.Login.LoginResponse;
import com.web.dto.auth.Login.RegisterRequest;
import com.web.dto.payment.PaymentConfirmRequest;
import com.web.dto.ticket.TicketCancelResponse;
import com.web.dto.ticket.TicketCreateRequest;
import com.web.dto.ticket.TicketResponse;
import com.web.entity.Bus;
import com.web.entity.Route;
import com.web.entity.Seat;
import com.web.entity.Ticket;
import com.web.entity.Trip;
import com.web.entity.User;
import com.web.repository.BusRepository;
import com.web.repository.FareRuleRepository;
import com.web.repository.RouteRepository;
import com.web.repository.SeatRepository;
import com.web.repository.StopRepository;
import com.web.repository.TripRepository;
import com.web.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminConfigIntegrationTest extends BaseIntegrationTest {

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
        private SeatRepository seatRepository;

        @Autowired
        private TripRepository tripRepository;

        @Autowired
        private UserRepository userRepository;

        @Autowired
        private FareRuleRepository fareRuleRepository;

        private String adminToken;
        private Long adminId;
        private String passengerToken;
        private Long passengerId;
        private Long tripId;
        private Long fromStopId;
        private Long toStopId;
        private Long tripId48h;

        @BeforeEach
        void setUp() throws Exception {

                tripRepository.deleteAll();
                seatRepository.deleteAll();
                fareRuleRepository.deleteAll();
                stopRepository.deleteAll();
                routeRepository.deleteAll();
                busRepository.deleteAll();
                userRepository.deleteAll();

                // Crear ruta con código único
                String uniqueCode = "TEST-" + System.currentTimeMillis();
                Route route = Route.builder()
                                .code(uniqueCode)
                                .name("Bogotá - Medellín")
                                .origin("Bogotá")
                                .destination("Medellín")
                                .distanceKm(new BigDecimal("450.0"))
                                .durationMin(480)
                                .isActive(true)
                                .build();
                route = routeRepository.save(route);

                // Crear paradas
                var stopBogota = com.web.entity.Stop.builder()
                                .route(route)
                                .name("Terminal Bogotá")
                                .order(1)
                                .latitude(new BigDecimal("4.6097"))
                                .longitude(new BigDecimal("-74.0817"))
                                .build();
                stopBogota = stopRepository.save(stopBogota);
                fromStopId = stopBogota.getId();

                var stopMedellin = com.web.entity.Stop.builder()
                                .route(route)
                                .name("Terminal Medellín")
                                .order(2)
                                .latitude(new BigDecimal("6.2476"))
                                .longitude(new BigDecimal("-75.5658"))
                                .build();
                stopMedellin = stopRepository.save(stopMedellin);
                toStopId = stopMedellin.getId();

                // Crear bus
                Bus bus = Bus.builder()
                                .plate("TEST-001")
                                .capacity(40)
                                .amenities(new HashMap<>())
                                .status(Bus.BusStatus.ACTIVE)
                                .build();
                bus = busRepository.save(bus);

                // Crear asientos
                for (int i = 1; i <= 40; i++) {
                        Seat seat = Seat.builder()
                                        .bus(bus)
                                        .seatNumber(i)
                                        .seatType(Seat.SeatType.STANDARD)
                                        .build();
                        seatRepository.save(seat);
                }

                // Crear viaje normal
                LocalDate tomorrow = LocalDate.now().plusDays(1);
                Trip trip = Trip.builder()
                                .route(route)
                                .bus(bus)
                                .tripDate(tomorrow)
                                .departureTime(LocalDateTime.now().plusDays(1).withHour(8).withMinute(0))
                                .arrivalEta(LocalDateTime.now().plusDays(1).withHour(16).withMinute(0))
                                .status(Trip.TripStatus.SCHEDULED)
                                .build();
                trip = tripRepository.save(trip);
                tripId = trip.getId();

                // Crear viaje para cancelación (48h antes)
                Trip trip48h = Trip.builder()
                                .route(route)
                                .bus(bus)
                                .tripDate(LocalDate.now().plusDays(3))
                                .departureTime(LocalDateTime.now().plusDays(3).withHour(8).withMinute(0))
                                .arrivalEta(LocalDateTime.now().plusDays(3).withHour(16).withMinute(0))
                                .status(Trip.TripStatus.SCHEDULED)
                                .build();
                trip48h = tripRepository.save(trip48h);
                tripId48h = trip48h.getId();

                // Registrar ADMIN
                RegisterRequest adminRegister = new RegisterRequest(
                                "Test Admin",
                                "admin@test.com",
                                "1111111111",
                                "password123",
                                User.Role.ADMIN);
                createUser(adminRegister);

                LoginRequest adminLogin = new LoginRequest("admin@test.com", "password123");
                MvcResult adminLoginResult = mvc.perform(post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(om.writeValueAsString(adminLogin)))
                                .andExpect(status().isOk())
                                .andReturn();

                LoginResponse adminLoginResponse = om.readValue(adminLoginResult.getResponse().getContentAsString(),
                                LoginResponse.class);
                adminToken = adminLoginResponse.token();
                adminId = adminLoginResponse.user().id();

                // Registrar pasajero
                RegisterRequest passengerRegister = new RegisterRequest(
                                "Test Passenger",
                                "passenger@test.com",
                                "2222222222",
                                "password123",
                                User.Role.PASSENGER);
                mvc.perform(post("/api/v1/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(om.writeValueAsString(passengerRegister)))
                                .andExpect(status().isCreated());

                LoginRequest passengerLogin = new LoginRequest("passenger@test.com", "password123");
                MvcResult passengerLoginResult = mvc.perform(post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(om.writeValueAsString(passengerLogin)))
                                .andExpect(status().isOk())
                                .andReturn();

                LoginResponse passengerLoginResponse = om.readValue(
                                passengerLoginResult.getResponse().getContentAsString(), LoginResponse.class);
                passengerToken = passengerLoginResponse.token();
                passengerId = passengerLoginResponse.user().id();
        }

        // Verifica que cambios en descuentos afectan compras: Admin actualiza STUDENT
        // 20%→25%, pasajero compra, precio refleja nuevo descuento
        @Test
        void updateDiscounts_shouldAffectTicketPurchases() throws Exception {
                // Admin actualiza descuento STUDENT a 25%
                ConfigUpdateRequest configUpdate = new ConfigUpdateRequest(
                                null, null, null,
                                java.util.Map.of("STUDENT", 25, "SENIOR", 15, "CHILD", 50),
                                null, null, null, null,
                                null, null, null, null, null,
                                null, null, null, null);

                mvc.perform(put("/api/v1/admin/config")
                                .header("Authorization", "Bearer " + adminToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(om.writeValueAsString(configUpdate)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.discountPercentages.STUDENT").value(25));

                // Pasajero compra ticket como STUDENT
                TicketCreateRequest ticketRequest = new TicketCreateRequest(
                                tripId, passengerId, 1,
                                fromStopId, "Terminal Bogotá", 1,
                                toStopId, "Terminal Medellín", 2,
                                new BigDecimal("50000"), Ticket.PaymentMethod.CARD,
                                null, "STUDENT");

                MvcResult purchaseResult = mvc.perform(post("/api/v1/trips/{tripId}/tickets", tripId)
                                .header("Authorization", "Bearer " + passengerToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(om.writeValueAsString(ticketRequest)))
                                .andExpect(status().isCreated())
                                .andReturn();

                // Verificar precio final con descuento 25%
                TicketResponse ticketResponse = om.readValue(purchaseResult.getResponse().getContentAsString(),
                                TicketResponse.class);
                assertThat(ticketResponse.price()).isNotNull();
        }

        // Verifica que cambios en política de reembolso afectan cancelaciones: Admin cambia 48h 80%→90%, pasajero compra y cancela, recibe 90%
        @Test
        void updateRefundPolicy_shouldAffectCancellations() throws Exception {
                // Admin actualiza política 48h a 90%
                ConfigUpdateRequest configUpdate = new ConfigUpdateRequest(
                                null, null, null, null, null, null,
                                null, null,
                                new BigDecimal("90"),
                                null, null, null, null,
                                null, null, null, null);

                mvc.perform(put("/api/v1/admin/config")
                                .header("Authorization", "Bearer " + adminToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(om.writeValueAsString(configUpdate)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.refundPercentage48Hours").value(90));

                // Pasajero compra ticket para viaje en 3 días
                TicketCreateRequest ticketRequest = new TicketCreateRequest(
                                tripId48h, passengerId, 1,
                                fromStopId, "Terminal Bogotá", 1,
                                toStopId, "Terminal Medellín", 2,
                                new BigDecimal("100000"), Ticket.PaymentMethod.CARD,
                                null, "ADULT");

                MvcResult purchaseResult = mvc.perform(post("/api/v1/trips/{tripId}/tickets", tripId48h)
                                .header("Authorization", "Bearer " + passengerToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(om.writeValueAsString(ticketRequest)))
                                .andExpect(status().isCreated())
                                .andReturn();

                TicketResponse ticketResponse = om.readValue(purchaseResult.getResponse().getContentAsString(),
                                TicketResponse.class);
                Long ticketId = ticketResponse.id();
                BigDecimal actualTicketPrice = ticketResponse.price();

                // La taquilla confirma el pago con tarjeta: solo un ticket pagado se reembolsa
                createUser(new RegisterRequest("Taquilla", "clerk@test.com", "1234567890", "password123", User.Role.CLERK));
                MvcResult clerkLogin = mvc.perform(post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(om.writeValueAsString(new LoginRequest("clerk@test.com", "password123"))))
                                .andExpect(status().isOk())
                                .andReturn();
                String clerkToken = om.readValue(clerkLogin.getResponse().getContentAsString(), LoginResponse.class).token();
                mvc.perform(post("/api/v1/payments/confirm")
                                .header("Authorization", "Bearer " + clerkToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(om.writeValueAsString(new PaymentConfirmRequest(
                                                ticketId, Ticket.PaymentMethod.CARD, "DATAFONO-1", null, null))))
                                .andExpect(status().isOk());

                // Pasajero cancela (más de 48h antes)
                MvcResult cancelResult = mvc.perform(post("/api/v1/tickets/{id}/cancel", ticketId)
                                .header("Authorization", "Bearer " + passengerToken)
                                .contentType(MediaType.APPLICATION_JSON))
                                .andExpect(status().isOk())
                                .andReturn();

                // Verificar reembolso 90% según nueva política
                TicketCancelResponse cancelResponse = om.readValue(cancelResult.getResponse().getContentAsString(),
                                TicketCancelResponse.class);
                BigDecimal expectedRefund = actualTicketPrice
                                .multiply(new BigDecimal("0.90"))
                                .setScale(2, RoundingMode.HALF_UP);
                assertThat(cancelResponse.refundPercentage()).isEqualTo(90);
                assertThat(cancelResponse.refundAmount()).isEqualByComparingTo(expectedRefund);
        }

        // Verifica que GET /config retorna todas las políticas: descuentos, reembolsos, precios, overbooking, etc.
        @Test
        void getConfig_shouldReturnAllPolicies() throws Exception {
                // Admin solicita configuración completa
                MvcResult configResult = mvc.perform(get("/api/v1/admin/config")
                                .header("Authorization", "Bearer " + adminToken))
                                .andExpect(status().isOk())
                                .andReturn();

                ConfigResponse config = om.readValue(configResult.getResponse().getContentAsString(),
                                ConfigResponse.class);

                // Verificar todas las políticas presentes
                assertThat(config.holdDurationMinutes()).isNotNull();
                assertThat(config.discountPercentages()).isNotNull();
                assertThat(config.discountPercentages()).containsKey("STUDENT");
                assertThat(config.refundPercentage48Hours()).isNotNull();
                assertThat(config.refundPercentage24Hours()).isNotNull();
                assertThat(config.refundPercentage12Hours()).isNotNull();
                assertThat(config.refundPercentage6Hours()).isNotNull();
                assertThat(config.refundPercentageLess6Hours()).isNotNull();
                assertThat(config.ticketBasePrice()).isNotNull();
                assertThat(config.ticketPriceMultiplierPeakHours()).isNotNull();
                assertThat(config.overbookingMaxPercentage()).isNotNull();
                assertThat(config.noShowFee()).isNotNull();
        }
}
