package com.web.integration.userstories;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.dto.auth.Login.LoginRequest;
import com.web.dto.auth.Login.LoginResponse;
import com.web.dto.auth.Login.RegisterRequest;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PassengerCancellationIntegrationTest extends BaseIntegrationTest {

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

        private String passengerToken;
        private Long passengerId;
        private Long tripId48h;
        private Long tripId24h;
        private Long tripId12h;
        private Long tripId6h;
        private Long tripIdLess6h;
        private Long fromStopId;
        private Long toStopId;

        @BeforeEach
        void setUp() throws Exception {
                // Limpiar datos (orden importante: primero dependientes, luego padres)
                tripRepository.deleteAll();
                seatRepository.deleteAll();
                fareRuleRepository.deleteAll(); // Eliminar antes de rutas/paradas
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

                // Crear FareRule con precio exacto de 100000
                com.web.entity.FareRule fareRule = com.web.entity.FareRule.builder()
                                .route(route)
                                .fromStop(stopBogota)
                                .toStop(stopMedellin)
                                .basePrice(new BigDecimal("100000"))
                                .discounts(null)
                                .dynamicPricingEnabled(false)
                                .build();
                fareRuleRepository.save(fareRule);

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

                LocalDateTime now = LocalDateTime.now();

                // Crear viajes con diferentes tiempos hasta salida
                Trip trip48h = Trip.builder()
                                .route(route)
                                .bus(bus)
                                .tripDate(now.plusHours(50).toLocalDate())
                                .departureTime(now.plusHours(50)) // 50 horas = más de 48h
                                .arrivalEta(now.plusHours(58))
                                .status(Trip.TripStatus.SCHEDULED)
                                .build();
                trip48h = tripRepository.save(trip48h);
                tripId48h = trip48h.getId();

                Trip trip24h = Trip.builder()
                                .route(route)
                                .bus(bus)
                                .tripDate(now.plusHours(30).toLocalDate())
                                .departureTime(now.plusHours(30)) // 30 horas = más de 24h pero menos de 48h
                                .arrivalEta(now.plusHours(38))
                                .status(Trip.TripStatus.SCHEDULED)
                                .build();
                trip24h = tripRepository.save(trip24h);
                tripId24h = trip24h.getId();

                Trip trip12h = Trip.builder()
                                .route(route)
                                .bus(bus)
                                .tripDate(now.plusHours(15).toLocalDate())
                                .departureTime(now.plusHours(15)) // 15 horas = más de 12h pero menos de 24h
                                .arrivalEta(now.plusHours(23))
                                .status(Trip.TripStatus.SCHEDULED)
                                .build();
                trip12h = tripRepository.save(trip12h);
                tripId12h = trip12h.getId();

                Trip trip6h = Trip.builder()
                                .route(route)
                                .bus(bus)
                                .tripDate(now.plusHours(8).toLocalDate())
                                .departureTime(now.plusHours(8)) // 8 horas = más de 6h pero menos de 12h
                                .arrivalEta(now.plusHours(16))
                                .status(Trip.TripStatus.SCHEDULED)
                                .build();
                trip6h = tripRepository.save(trip6h);
                tripId6h = trip6h.getId();

                Trip tripLess6h = Trip.builder()
                                .route(route)
                                .bus(bus)
                                .tripDate(now.plusHours(4).toLocalDate())
                                .departureTime(now.plusHours(4)) // 4 horas = menos de 6h
                                .arrivalEta(now.plusHours(12))
                                .status(Trip.TripStatus.SCHEDULED)
                                .build();
                tripLess6h = tripRepository.save(tripLess6h);
                tripIdLess6h = tripLess6h.getId();

                // Registrar y loguear pasajero
                RegisterRequest registerRequest = new RegisterRequest(
                                "Test Passenger",
                                "passenger@test.com",
                                "1234567890",
                                "password123",
                                User.Role.PASSENGER);
                mvc.perform(post("/api/v1/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(om.writeValueAsString(registerRequest)))
                                .andExpect(status().isCreated());

                LoginRequest loginRequest = new LoginRequest("passenger@test.com", "password123");
                MvcResult loginResult = mvc.perform(post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(om.writeValueAsString(loginRequest)))
                                .andExpect(status().isOk())
                                .andReturn();

                LoginResponse loginResponse = om.readValue(loginResult.getResponse().getContentAsString(),
                                LoginResponse.class);
                passengerToken = loginResponse.token();
                passengerId = loginResponse.user().id();
        }

        // Verifica cancelación 48h+: comprar ticket para viaje en 3 días, cancelar, debe reembolsar 90%
        @Test
        void cancelTicket_48HoursBefore_shouldRefund80Percent() throws Exception {
                // Comprar ticket para viaje en 3 días (más de 48h)
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

                // Cancelar ticket
                MvcResult cancelResult = mvc.perform(post("/api/v1/tickets/{id}/cancel", ticketId)
                                .header("Authorization", "Bearer " + passengerToken)
                                .contentType(MediaType.APPLICATION_JSON))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.refundPercentage").value(90))
                                .andReturn();

                TicketCancelResponse cancelResponse = om.readValue(cancelResult.getResponse().getContentAsString(),
                                TicketCancelResponse.class);
                // Verificar reembolso 90% (política 48h+)
                BigDecimal expectedRefund = actualTicketPrice
                                .multiply(new BigDecimal("0.90"))
                                .setScale(2, RoundingMode.HALF_UP);
                assertThat(cancelResponse.refundAmount()).isEqualByComparingTo(expectedRefund);
                assertThat(cancelResponse.refundPercentage()).isEqualTo(90);
        }

        // Verifica cancelación 24-48h: comprar ticket para viaje en 2 días, cancelar, debe reembolsar 70%
        @Test
        void cancelTicket_24HoursBefore_shouldRefund50Percent() throws Exception {
                // Comprar ticket para viaje en 2 días (24-48h)
                TicketCreateRequest ticketRequest = new TicketCreateRequest(
                                tripId24h, passengerId, 2,
                                fromStopId, "Terminal Bogotá", 1,
                                toStopId, "Terminal Medellín", 2,
                                new BigDecimal("100000"), Ticket.PaymentMethod.CARD,
                                null, "ADULT");

                MvcResult purchaseResult = mvc.perform(post("/api/v1/trips/{tripId}/tickets", tripId24h)
                                .header("Authorization", "Bearer " + passengerToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(om.writeValueAsString(ticketRequest)))
                                .andExpect(status().isCreated())
                                .andReturn();

                TicketResponse ticketResponse = om.readValue(purchaseResult.getResponse().getContentAsString(),
                                TicketResponse.class);
                Long ticketId = ticketResponse.id();
                BigDecimal actualTicketPrice = ticketResponse.price();

                // Cancelar ticket
                MvcResult cancelResult = mvc.perform(post("/api/v1/tickets/{id}/cancel", ticketId)
                                .header("Authorization", "Bearer " + passengerToken)
                                .contentType(MediaType.APPLICATION_JSON))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.refundPercentage").value(70))
                                .andReturn();

                TicketCancelResponse cancelResponse = om.readValue(cancelResult.getResponse().getContentAsString(),
                                TicketCancelResponse.class);
                // Verificar reembolso 70%
                BigDecimal expectedRefund = actualTicketPrice
                                .multiply(new BigDecimal("0.70"))
                                .setScale(2, RoundingMode.HALF_UP);
                assertThat(cancelResponse.refundAmount()).isEqualByComparingTo(expectedRefund);
        }

        // Verifica cancelación 12-24h: comprar ticket para viaje en 1 día, cancelar, debe reembolsar 50%
        @Test
        void cancelTicket_12HoursBefore_shouldRefund50Percent() throws Exception {
                // Comprar ticket para viaje en 1 día (12-24h)
                TicketCreateRequest ticketRequest = new TicketCreateRequest(
                                tripId12h, passengerId, 3,
                                fromStopId, "Terminal Bogotá", 1,
                                toStopId, "Terminal Medellín", 2,
                                new BigDecimal("100000"), Ticket.PaymentMethod.CARD,
                                null, "ADULT");

                MvcResult purchaseResult = mvc.perform(post("/api/v1/trips/{tripId}/tickets", tripId12h)
                                .header("Authorization", "Bearer " + passengerToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(om.writeValueAsString(ticketRequest)))
                                .andExpect(status().isCreated())
                                .andReturn();

                TicketResponse ticketResponse = om.readValue(purchaseResult.getResponse().getContentAsString(),
                                TicketResponse.class);
                Long ticketId = ticketResponse.id();
                BigDecimal actualTicketPrice = ticketResponse.price();

                // Cancelar ticket
                MvcResult cancelResult = mvc.perform(post("/api/v1/tickets/{id}/cancel", ticketId)
                                .header("Authorization", "Bearer " + passengerToken)
                                .contentType(MediaType.APPLICATION_JSON))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.refundPercentage").value(50))
                                .andReturn();

                TicketCancelResponse cancelResponse = om.readValue(cancelResult.getResponse().getContentAsString(),
                                TicketCancelResponse.class);
                // Verificar reembolso 50%
                BigDecimal expectedRefund = actualTicketPrice
                                .multiply(new BigDecimal("0.50"))
                                .setScale(2, RoundingMode.HALF_UP);
                assertThat(cancelResponse.refundAmount()).isEqualByComparingTo(expectedRefund);
        }

        // Verifica cancelación 6-12h: comprar ticket para viaje en 8 horas, cancelar, debe reembolsar 30%
        @Test
        void cancelTicket_6HoursBefore_shouldRefund30Percent() throws Exception {
                // Comprar ticket para viaje en 8 horas (6-12h)
                TicketCreateRequest ticketRequest = new TicketCreateRequest(
                                tripId6h, passengerId, 4,
                                fromStopId, "Terminal Bogotá", 1,
                                toStopId, "Terminal Medellín", 2,
                                new BigDecimal("100000"), Ticket.PaymentMethod.CARD,
                                null, "ADULT");

                MvcResult purchaseResult = mvc.perform(post("/api/v1/trips/{tripId}/tickets", tripId6h)
                                .header("Authorization", "Bearer " + passengerToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(om.writeValueAsString(ticketRequest)))
                                .andExpect(status().isCreated())
                                .andReturn();

                TicketResponse ticketResponse = om.readValue(purchaseResult.getResponse().getContentAsString(),
                                TicketResponse.class);
                Long ticketId = ticketResponse.id();
                BigDecimal actualTicketPrice = ticketResponse.price();

                // Cancelar ticket
                MvcResult cancelResult = mvc.perform(post("/api/v1/tickets/{id}/cancel", ticketId)
                                .header("Authorization", "Bearer " + passengerToken)
                                .contentType(MediaType.APPLICATION_JSON))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.refundPercentage").value(30))
                                .andReturn();

                TicketCancelResponse cancelResponse = om.readValue(cancelResult.getResponse().getContentAsString(),
                                TicketCancelResponse.class);
                // Verificar reembolso 30%
                BigDecimal expectedRefund = actualTicketPrice
                                .multiply(new BigDecimal("0.30"))
                                .setScale(2, RoundingMode.HALF_UP);
                assertThat(cancelResponse.refundAmount()).isEqualByComparingTo(expectedRefund);
        }

        // Verifica cancelación <6h: comprar ticket para viaje en 3 horas, cancelar, debe reembolsar 0%
        @Test
        void cancelTicket_Less6Hours_shouldRefund0Percent() throws Exception {
                // Comprar ticket para viaje en 3 horas (menos de 6h)
                TicketCreateRequest ticketRequest = new TicketCreateRequest(
                                tripIdLess6h, passengerId, 5,
                                fromStopId, "Terminal Bogotá", 1,
                                toStopId, "Terminal Medellín", 2,
                                new BigDecimal("100000"), Ticket.PaymentMethod.CARD,
                                null, "ADULT");

                MvcResult purchaseResult = mvc.perform(post("/api/v1/trips/{tripId}/tickets", tripIdLess6h)
                                .header("Authorization", "Bearer " + passengerToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(om.writeValueAsString(ticketRequest)))
                                .andExpect(status().isCreated())
                                .andReturn();

                TicketResponse ticketResponse = om.readValue(purchaseResult.getResponse().getContentAsString(),
                                TicketResponse.class);
                Long ticketId = ticketResponse.id();

                // Cancelar ticket
                mvc.perform(post("/api/v1/tickets/{id}/cancel", ticketId)
                                .header("Authorization", "Bearer " + passengerToken)
                                .contentType(MediaType.APPLICATION_JSON))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.refundPercentage").value(0))
                                .andExpect(jsonPath("$.refundAmount").value(0.0));
        }
}
