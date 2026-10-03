package com.web.integration.userstories;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.dto.auth.Login.LoginRequest;
import com.web.dto.auth.Login.LoginResponse;
import com.web.dto.auth.Login.RegisterRequest;
import com.web.dto.baggage.BaggageCreateRequest;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ClerkBaggageIntegrationTest extends BaseIntegrationTest {

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

        private String clerkToken;
        private Long clerkId;
        private Long passengerId;
        private Long tripId;
        private Long fromStopId;
        private Long toStopId;

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

                // Crear viaje
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

                // Registrar CLERK
                RegisterRequest clerkRegister = new RegisterRequest(
                                "Test Clerk",
                                "clerk@test.com",
                                "1111111111",
                                "password123",
                                User.Role.CLERK);
                createUser(clerkRegister);

                LoginRequest clerkLogin = new LoginRequest("clerk@test.com", "password123");
                MvcResult clerkLoginResult = mvc.perform(post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(om.writeValueAsString(clerkLogin)))
                                .andExpect(status().isOk())
                                .andReturn();

                LoginResponse clerkLoginResponse = om.readValue(clerkLoginResult.getResponse().getContentAsString(),
                                LoginResponse.class);
                clerkToken = clerkLoginResponse.token();
                clerkId = clerkLoginResponse.user().id();

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
                passengerId = passengerLoginResponse.user().id();
        }

        // Verifica registro de equipaje dentro del límite: 15kg (límite 20kg), sin exceso, genera tag único BAG-XXX
        @Test
        void purchaseTicket_withBaggage_shouldRegisterBaggageAndCalculateFee() throws Exception {
                // Comprar ticket con equipaje 15kg (sin exceso)
                BaggageCreateRequest baggageRequest = new BaggageCreateRequest(new BigDecimal("15.0"), null);
                TicketCreateRequest ticketRequest = new TicketCreateRequest(
                                tripId, passengerId, 1,
                                fromStopId, "Terminal Bogotá", 1,
                                toStopId, "Terminal Medellín", 2,
                                new BigDecimal("50000"), Ticket.PaymentMethod.CARD,
                                baggageRequest, "ADULT");

                MvcResult purchaseResult = mvc.perform(post("/api/v1/trips/{tripId}/tickets", tripId)
                                .header("Authorization", "Bearer " + clerkToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(om.writeValueAsString(ticketRequest)))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.baggage").exists())
                                .andExpect(jsonPath("$.baggage.weightKg").value(15.0))
                                .andExpect(jsonPath("$.baggage.excessFee").value(0.0))
                                .andExpect(jsonPath("$.baggage.tagCode").exists())
                                .andExpect(jsonPath("$.baggage.tagCode")
                                                .value(org.hamcrest.Matchers.startsWith("BAG-")))
                                .andReturn();

                // Verificar equipaje registrado con tag único, sin cargo extra
                TicketResponse ticketResponse = om.readValue(purchaseResult.getResponse().getContentAsString(),
                                TicketResponse.class);
                assertThat(ticketResponse.baggage()).isNotNull();
                assertThat(ticketResponse.baggage().weightKg()).isEqualByComparingTo(new BigDecimal("15.0"));
                assertThat(ticketResponse.baggage().excessFee()).isEqualByComparingTo(BigDecimal.ZERO);
                assertThat(ticketResponse.baggage().tagCode()).startsWith("BAG-");
        }

        // Verifica cálculo de cargo por exceso: 25kg (límite 20kg = 5kg exceso), cargo esperado 5kg × $2,500 = $12,500
        @Test
        void purchaseTicket_withExcessBaggage_shouldCalculateExcessFee() throws Exception {
                // Comprar ticket con 25kg (5kg exceso)
                BaggageCreateRequest baggageRequest = new BaggageCreateRequest(new BigDecimal("25.0"), null);
                TicketCreateRequest ticketRequest = new TicketCreateRequest(
                                tripId, passengerId, 2,
                                fromStopId, "Terminal Bogotá", 1,
                                toStopId, "Terminal Medellín", 2,
                                new BigDecimal("50000"), Ticket.PaymentMethod.CARD,
                                baggageRequest, "ADULT");

                MvcResult purchaseResult = mvc.perform(post("/api/v1/trips/{tripId}/tickets", tripId)
                                .header("Authorization", "Bearer " + clerkToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(om.writeValueAsString(ticketRequest)))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.baggage").exists())
                                .andExpect(jsonPath("$.baggage.weightKg").value(25.0))
                                .andExpect(jsonPath("$.baggage.excessFee").value(12500.0))
                                .andExpect(jsonPath("$.baggage.tagCode").exists())
                                .andReturn();

                // Verificar cargo por exceso calculado correctamente
                TicketResponse ticketResponse = om.readValue(purchaseResult.getResponse().getContentAsString(),
                                TicketResponse.class);
                assertThat(ticketResponse.baggage().excessFee()).isEqualByComparingTo(new BigDecimal("12500.0"));
        }

        // Verifica que cada equipaje tiene tag único BAG-XXX: crear 3 tickets con equipaje, todos deben tener tags diferentes
        @Test
        void purchaseTicket_withMultipleBaggage_shouldGenerateUniqueTags() throws Exception {
                // Crear 3 tickets con equipaje y coleccionar tags
                Set<String> tagCodes = new HashSet<>();
                int numberOfTickets = 3;

                for (int i = 1; i <= numberOfTickets; i++) {
                        BaggageCreateRequest baggageRequest = new BaggageCreateRequest(new BigDecimal("15.0"), null);
                        TicketCreateRequest ticketRequest = new TicketCreateRequest(
                                        tripId, passengerId, i,
                                        fromStopId, "Terminal Bogotá", 1,
                                        toStopId, "Terminal Medellín", 2,
                                        new BigDecimal("50000"), Ticket.PaymentMethod.CARD,
                                        baggageRequest, "ADULT");

                        MvcResult purchaseResult = mvc.perform(post("/api/v1/trips/{tripId}/tickets", tripId)
                                        .header("Authorization", "Bearer " + clerkToken)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(om.writeValueAsString(ticketRequest)))
                                        .andExpect(status().isCreated())
                                        .andReturn();

                        TicketResponse ticketResponse = om.readValue(purchaseResult.getResponse().getContentAsString(),
                                        TicketResponse.class);
                        String tagCode = ticketResponse.baggage().tagCode();

                        // Verificar tag único (no se repite)
                        assertThat(tagCodes).doesNotContain(tagCode);
                        tagCodes.add(tagCode);

                        // Validar formato BAG-XXX
                        assertThat(tagCode).startsWith("BAG-");
                }

                assertThat(tagCodes.size()).isEqualTo(numberOfTickets);
        }
}
