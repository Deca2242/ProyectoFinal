package com.web.integration.userstories;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.dto.auth.Login.LoginRequest;
import com.web.dto.auth.Login.LoginResponse;
import com.web.dto.auth.Login.RegisterRequest;
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
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PassengerTicketQrIntegrationTest extends BaseIntegrationTest {

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

        // Registrar y loguear pasajero
        RegisterRequest registerRequest = new RegisterRequest(
                "Test Passenger",
                "passenger@test.com",
                "1234567890",
                "password123",
                User.Role.PASSENGER
        );
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

        LoginResponse loginResponse = om.readValue(loginResult.getResponse().getContentAsString(), LoginResponse.class);
        passengerToken = loginResponse.token();
        passengerId = loginResponse.user().id();
    }

    // Verifica que cada ticket tiene QR único: crear 5 tickets, todos deben tener QR diferentes
    @Test
    void purchaseTicket_shouldGenerateUniqueQrCode() throws Exception {
        // Crear 5 tickets y coleccionar QR codes
        Set<String> qrCodes = new HashSet<>();
        int numberOfTickets = 5;

        for (int i = 1; i <= numberOfTickets; i++) {
            TicketCreateRequest ticketRequest = new TicketCreateRequest(
                    tripId, passengerId, i,
                    fromStopId, "Terminal Bogotá", 1,
                    toStopId, "Terminal Medellín", 2,
                    new BigDecimal("50000"), Ticket.PaymentMethod.CARD,
                    null, "ADULT"
            );

            MvcResult purchaseResult = mvc.perform(post("/api/v1/trips/{tripId}/tickets", tripId)
                            .header("Authorization", "Bearer " + passengerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(ticketRequest)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.qrCode").exists())
                    .andExpect(jsonPath("$.qrCode").isNotEmpty())
                    .andReturn();

            TicketResponse ticketResponse = om.readValue(purchaseResult.getResponse().getContentAsString(), TicketResponse.class);
            String qrCode = ticketResponse.qrCode();

            // Verificar QR único (no se repite)
            assertThat(qrCodes).doesNotContain(qrCode);
            qrCodes.add(qrCode);

            // Validar QR no vacío (Base64)
            assertThat(qrCode).isNotBlank();
            assertThat(qrCode.length()).isGreaterThan(10);
        }

        assertThat(qrCodes.size()).isEqualTo(numberOfTickets);
    }

    // Verifica que GET /tickets/{id} incluye QR: comprar ticket, consultar por ID, debe incluir QR
    @Test
    void getTicket_shouldIncludeQrCode() throws Exception {
        // Comprar ticket
        TicketCreateRequest ticketRequest = new TicketCreateRequest(
                tripId, passengerId, 1,
                fromStopId, "Terminal Bogotá", 1,
                toStopId, "Terminal Medellín", 2,
                new BigDecimal("50000"), Ticket.PaymentMethod.CARD,
                null, "ADULT"
        );

        MvcResult purchaseResult = mvc.perform(post("/api/v1/trips/{tripId}/tickets", tripId)
                        .header("Authorization", "Bearer " + passengerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(ticketRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        TicketResponse ticketResponse = om.readValue(purchaseResult.getResponse().getContentAsString(), TicketResponse.class);
        Long ticketId = ticketResponse.id();
        String qrCode = ticketResponse.qrCode();

        // Consultar ticket por ID y verificar incluye QR
        mvc.perform(get("/api/v1/tickets/{id}", ticketId)
                        .header("Authorization", "Bearer " + passengerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ticketId))
                .andExpect(jsonPath("$.qrCode").value(qrCode))
                .andExpect(jsonPath("$.qrCode").exists());
    }

    // Verifica que GET /tickets/my-tickets incluye QR de todos: comprar 3 tickets, consultar lista, todos deben tener QR
    @Test
    void getMyTickets_shouldIncludeQrCodes() throws Exception {
        // Comprar 3 tickets
        for (int i = 1; i <= 3; i++) {
            TicketCreateRequest ticketRequest = new TicketCreateRequest(
                    tripId, passengerId, i,
                    fromStopId, "Terminal Bogotá", 1,
                    toStopId, "Terminal Medellín", 2,
                    new BigDecimal("50000"), Ticket.PaymentMethod.CARD,
                    null, "ADULT"
            );

            mvc.perform(post("/api/v1/trips/{tripId}/tickets", tripId)
                            .header("Authorization", "Bearer " + passengerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(ticketRequest)))
                    .andExpect(status().isCreated());
        }

        // Consultar todos los tickets del usuario
        MvcResult myTicketsResult = mvc.perform(get("/api/v1/tickets/my-tickets")
                        .header("Authorization", "Bearer " + passengerToken))
                .andExpect(status().isOk())
                .andReturn();

        List<TicketResponse> tickets = om.readValue(
                myTicketsResult.getResponse().getContentAsString(),
                om.getTypeFactory().constructCollectionType(List.class, TicketResponse.class)
        );

        assertThat(tickets).hasSize(3);
        for (TicketResponse ticket : tickets) {
            assertThat(ticket.qrCode()).isNotBlank();
        }
    }
}
