package com.web.integration.userstories;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.dto.admin.ConfigResponse;
import com.web.dto.admin.ConfigUpdateRequest;
import com.web.dto.auth.Login.LoginRequest;
import com.web.dto.auth.Login.LoginResponse;
import com.web.dto.auth.Login.RegisterRequest;
import com.web.dto.ticket.TicketCreateRequest;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class DispatcherOverbookingIntegrationTest extends BaseIntegrationTest {

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
    private Long busId;
    private int busCapacity = 40;

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

        // Crear bus con capacidad 40
        Bus bus = Bus.builder()
                .plate("TEST-001")
                .capacity(busCapacity)
                .amenities(new HashMap<>())
                .status(Bus.BusStatus.ACTIVE)
                .build();
        bus = busRepository.save(bus);
        busId = bus.getId();

        // Crear asientos
        for (int i = 1; i <= busCapacity; i++) {
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

        // Registrar ADMIN
        RegisterRequest adminRegister = new RegisterRequest(
                "Test Admin",
                "admin@test.com",
                "1111111111",
                "password123",
                User.Role.ADMIN
        );
        createUser(adminRegister);

        LoginRequest adminLogin = new LoginRequest("admin@test.com", "password123");
        MvcResult adminLoginResult = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(adminLogin)))
                .andExpect(status().isOk())
                .andReturn();

        LoginResponse adminLoginResponse = om.readValue(adminLoginResult.getResponse().getContentAsString(), LoginResponse.class);
        adminToken = adminLoginResponse.token();
        adminId = adminLoginResponse.user().id();

        // Registrar pasajero
        RegisterRequest passengerRegister = new RegisterRequest(
                "Test Passenger",
                "passenger@test.com",
                "2222222222",
                "password123",
                User.Role.PASSENGER
        );
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

        LoginResponse passengerLoginResponse = om.readValue(passengerLoginResult.getResponse().getContentAsString(), LoginResponse.class);
        passengerToken = passengerLoginResponse.token();
        passengerId = passengerLoginResponse.user().id();
    }

    /* Verifica que overbooking permite vender más tickets: Admin configura 5%, bus 40 asientos → límite 42 tickets,
    comprar 42 OK, comprar 43 falla
    */
    @Test
    void configureOverbooking_shouldAffectTicketPurchases() throws Exception {
        // Admin configura overbooking 5% (bus 40 → límite 42 tickets)
        ConfigUpdateRequest configUpdate = new ConfigUpdateRequest(
                null, null, null, null, null, null,
                null, 0.05,
                null, null, null, null, null,
                null, null, null, null
        );

        mvc.perform(put("/api/v1/admin/config")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(configUpdate)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overbookingMaxPercentage").value(0.05));

        // Verificar configuración guardada
        MvcResult configResult = mvc.perform(get("/api/v1/admin/config")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();

        ConfigResponse config = om.readValue(configResult.getResponse().getContentAsString(), ConfigResponse.class);
        assertThat(config.overbookingMaxPercentage()).isEqualTo(0.05);

        // Comprar 42 tickets (hasta límite con overbooking)
        for (int i = 1; i <= 42; i++) {
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

        // Intentar comprar ticket #43 debe fallar (excede límite)
        TicketCreateRequest excessTicketRequest = new TicketCreateRequest(
                tripId, passengerId, 43,
                fromStopId, "Terminal Bogotá", 1,
                toStopId, "Terminal Medellín", 2,
                new BigDecimal("50000"), Ticket.PaymentMethod.CARD,
                null, "ADULT"
        );

        mvc.perform(post("/api/v1/trips/{tripId}/tickets", tripId)
                        .header("Authorization", "Bearer " + passengerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(excessTicketRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("overbooking")));
    }

    // Verifica que overbooking 0% solo permite capacidad exacta: Admin configura 0%, comprar 40 OK, comprar 41 falla
    @Test
    void configureOverbookingToZero_shouldNotAllowOverbooking() throws Exception {
        // Admin configura overbooking 0% (sin sobreventa)
        ConfigUpdateRequest configUpdate = new ConfigUpdateRequest(
                null, null, null, null, null, null,
                null, 0.0,
                null, null, null, null, null,
                null, null, null, null
        );

        mvc.perform(put("/api/v1/admin/config")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(configUpdate)))
                .andExpect(status().isOk());

        // Comprar 40 tickets (capacidad exacta)
        for (int i = 1; i <= busCapacity; i++) {
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

        // Intentar comprar ticket #41 debe fallar (sin overbooking)
        TicketCreateRequest excessTicketRequest = new TicketCreateRequest(
                tripId, passengerId, busCapacity + 1,
                fromStopId, "Terminal Bogotá", 1,
                toStopId, "Terminal Medellín", 2,
                new BigDecimal("50000"), Ticket.PaymentMethod.CARD,
                null, "ADULT"
        );

        mvc.perform(post("/api/v1/trips/{tripId}/tickets", tripId)
                        .header("Authorization", "Bearer " + passengerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(excessTicketRequest)))
                .andExpect(status().isBadRequest());
    }
}
