package com.web.integration.userstories;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.dto.auth.Login.LoginRequest;
import com.web.dto.auth.Login.LoginResponse;
import com.web.dto.auth.Login.RegisterRequest;
import com.web.dto.ticket.TicketCreateRequest;
import com.web.dto.ticket.TicketResponse;
import com.web.dto.ticket.reservations.SeatHoldRequest;
import com.web.dto.ticket.reservations.SeatHoldResponse;
import com.web.dto.trip.SeatStatusResponse;
import com.web.dto.trip.TripResponse;
import com.web.entity.Bus;
import com.web.entity.Route;
import com.web.entity.Seat;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PassengerTripFlowIntegrationTest extends BaseIntegrationTest {

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
        private Long routeId;
        private Long fromStopId;
        private Long toStopId;
        private Long busId;
        private Long tripId;

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
                routeId = route.getId();

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

                // Nota: No es necesario crear FareRule, el sistema usa
                // configService.getTicketBasePrice() como fallback

                // Crear bus con capacidad 40 y placa única
                String uniquePlate = "TEST-" + System.currentTimeMillis();
                Bus bus = Bus.builder()
                                .plate(uniquePlate)
                                .capacity(40)
                                .amenities(new HashMap<>())
                                .status(Bus.BusStatus.ACTIVE)
                                .build();
                bus = busRepository.save(bus);
                busId = bus.getId();

                // Crear asientos para el bus
                for (int i = 1; i <= 40; i++) {
                        Seat seat = Seat.builder()
                                        .bus(bus)
                                        .seatNumber(i)
                                        .seatType(i <= 36 ? Seat.SeatType.STANDARD : Seat.SeatType.PREFERENTIAL)
                                        .build();
                        seatRepository.save(seat);
                }

                // Crear viaje para mañana
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

        // Verifica flujo completo de compra: buscar viajes, ver asientos, crear hold,
        // comprar ticket, verificar asiento ocupado
        @Test
        void completePurchaseFlow_shouldSucceed() throws Exception {
                // Buscar viajes disponibles
                MvcResult searchResult = mvc.perform(get("/api/v1/trips")
                                .param("routeId", routeId.toString())
                                .param("date", LocalDate.now().plusDays(1).toString()))
                                .andExpect(status().isOk())
                                .andReturn();

                List<TripResponse> trips = om.readValue(
                                searchResult.getResponse().getContentAsString(),
                                om.getTypeFactory().constructCollectionType(List.class, TripResponse.class));

                assertThat(trips).isNotEmpty();
                TripResponse selectedTrip = trips.get(0);
                assertThat(selectedTrip.id()).isEqualTo(tripId);
                assertThat(selectedTrip.busCapacity()).isEqualTo(40);
                if (selectedTrip.soldSeats() != null) {
                        assertThat(selectedTrip.soldSeats()).isEqualTo(0);
                }

                // Ver disponibilidad de asientos para tramo
                MvcResult seatsResult = mvc.perform(get("/api/v1/trips/{tripId}/seats", tripId)
                                .param("fromStopId", fromStopId.toString())
                                .param("toStopId", toStopId.toString()))
                                .andExpect(status().isOk())
                                .andReturn();

                List<SeatStatusResponse> seats = om.readValue(
                                seatsResult.getResponse().getContentAsString(),
                                om.getTypeFactory().constructCollectionType(List.class, SeatStatusResponse.class));

                assertThat(seats).isNotEmpty();
                assertThat(seats.size()).isEqualTo(40);
                SeatStatusResponse availableSeat = seats.stream()
                                .filter(SeatStatusResponse::available)
                                .findFirst()
                                .orElseThrow(() -> new AssertionError("No hay asientos disponibles"));
                int seatNumber = availableSeat.seatNumber();

                // Crear hold de asiento
                SeatHoldRequest holdRequest = new SeatHoldRequest(passengerId, fromStopId, toStopId);
                MvcResult holdResult = mvc
                                .perform(post("/api/v1/trips/{tripId}/seats/{seatNumber}/hold", tripId, seatNumber)
                                                .header("Authorization", "Bearer " + passengerToken)
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(om.writeValueAsString(holdRequest)))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.seatNumber").value(seatNumber))
                                .andExpect(jsonPath("$.status").value("HOLD"))
                                .andReturn();

                SeatHoldResponse holdResponse = om.readValue(holdResult.getResponse().getContentAsString(),
                                SeatHoldResponse.class);
                assertThat(holdResponse.id()).isNotNull();
                assertThat(holdResponse.expiresAt()).isAfter(LocalDateTime.now());

                // Comprar ticket usando asiento en hold
                TicketCreateRequest ticketRequest = new TicketCreateRequest(
                                tripId, passengerId, seatNumber,
                                fromStopId, "Terminal Bogotá", 1,
                                toStopId, "Terminal Medellín", 2,
                                new BigDecimal("50000"), com.web.entity.Ticket.PaymentMethod.CARD,
                                null, "ADULT");

                MvcResult purchaseResult = mvc.perform(post("/api/v1/trips/{tripId}/tickets", tripId)
                                .header("Authorization", "Bearer " + passengerToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(om.writeValueAsString(ticketRequest)))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.seatNumber").value(seatNumber))
                                .andExpect(jsonPath("$.status").value("SOLD"))
                                .andExpect(jsonPath("$.qrCode").exists())
                                .andReturn();

                TicketResponse ticketResponse = om.readValue(purchaseResult.getResponse().getContentAsString(),
                                TicketResponse.class);
                assertThat(ticketResponse.id()).isNotNull();
                assertThat(ticketResponse.qrCode()).isNotBlank();

                // Verificar asiento ya no está disponible
                MvcResult seatsAfterResult = mvc.perform(get("/api/v1/trips/{tripId}/seats", tripId)
                                .param("fromStopId", fromStopId.toString())
                                .param("toStopId", toStopId.toString()))
                                .andExpect(status().isOk())
                                .andReturn();

                List<SeatStatusResponse> seatsAfter = om.readValue(
                                seatsAfterResult.getResponse().getContentAsString(),
                                om.getTypeFactory().constructCollectionType(List.class, SeatStatusResponse.class));

                SeatStatusResponse purchasedSeat = seatsAfter.stream()
                                .filter(s -> s.seatNumber() == seatNumber)
                                .findFirst()
                                .orElseThrow();
                assertThat(purchasedSeat.available()).isFalse();
                assertThat(purchasedSeat.status()).isEqualTo("OCCUPIED");
        }
}
