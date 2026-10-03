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

    /* Overbooking controlado (regla 4 / caso de uso 3): con 5 % configurado y bus de 40 sillas hay hasta 2 sillas extra,
       pero cada una requiere aprobación del DISPATCHER con ocupación > 95 % y menos de 30 min para salir.
       Sin aprobación, o por encima del máximo, la compra responde 403 */
    @Test
    void configureOverbooking_shouldAffectTicketPurchases() throws Exception {
        configureOverbooking(0.05);
        String dispatcherToken = dispatcherToken();

        // Con la salida lejana no se puede aprobar aunque el bus se llene
        sellSeats(1, busCapacity);
        approve(dispatcherToken).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("30 minutos")));

        // Sin aprobación la silla 41 no se vende
        purchase(busCapacity + 1).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("overbooking")));

        departSoon();

        // Primera aprobación: silla 41
        approve(dispatcherToken).andExpect(status().isOk())
                .andExpect(jsonPath("$.approvedSeatNumber").value(busCapacity + 1))
                .andExpect(jsonPath("$.maxExtraSeats").value(2));
        purchase(busCapacity + 1).andExpect(status().isCreated());

        // Segunda aprobación: silla 42 (40 + floor(40 * 0.05))
        approve(dispatcherToken).andExpect(status().isOk())
                .andExpect(jsonPath("$.approvedSeatNumber").value(busCapacity + 2));
        purchase(busCapacity + 2).andExpect(status().isCreated());

        // Se alcanzó el máximo: ni otra aprobación ni la silla 43
        approve(dispatcherToken).andExpect(status().isForbidden());
        purchase(busCapacity + 3).andExpect(status().isForbidden());

        // Un pasajero no puede aprobar overbooking
        approve(passengerToken).andExpect(status().isForbidden());
    }

    // Con overbooking 0 % no hay sillas extra aunque el bus esté lleno y falten menos de 30 minutos
    @Test
    void configureOverbookingToZero_shouldNotAllowOverbooking() throws Exception {
        configureOverbooking(0.0);
        String dispatcherToken = dispatcherToken();

        sellSeats(1, busCapacity);
        departSoon();

        approve(dispatcherToken).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("máximo")));
        purchase(busCapacity + 1).andExpect(status().isForbidden());
    }

    // Con ocupación de 95 % o menos no se aprueba overbooking
    @Test
    void approveOverbooking_withLowOccupancy_shouldReturn403() throws Exception {
        configureOverbooking(0.05);
        String dispatcherToken = dispatcherToken();
        sellSeats(1, 38); // 38/40 = 95 %
        departSoon();

        approve(dispatcherToken).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("95%")));
    }

    private void configureOverbooking(double percentage) throws Exception {
        ConfigUpdateRequest configUpdate = new ConfigUpdateRequest(
                null, null, null, null, null, null,
                null, percentage,
                null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null
        );
        mvc.perform(put("/api/v1/admin/config")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(configUpdate)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overbookingMaxPercentage").value(percentage));
    }

    private String dispatcherToken() throws Exception {
        createUser(new RegisterRequest("Despachador", "dispatcher.overbooking@test.com", "3001234567",
                "password123", User.Role.DISPATCHER));
        MvcResult result = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new LoginRequest("dispatcher.overbooking@test.com", "password123"))))
                .andExpect(status().isOk())
                .andReturn();
        return om.readValue(result.getResponse().getContentAsString(), LoginResponse.class).token();
    }

    private void departSoon() {
        Trip trip = tripRepository.findById(tripId).orElseThrow();
        trip.setDepartureTime(LocalDateTime.now().plusMinutes(20));
        tripRepository.saveAndFlush(trip);
    }

    private void sellSeats(int from, int to) throws Exception {
        for (int seat = from; seat <= to; seat++) {
            purchase(seat).andExpect(status().isCreated());
        }
    }

    private org.springframework.test.web.servlet.ResultActions purchase(int seat) throws Exception {
        TicketCreateRequest ticketRequest = new TicketCreateRequest(
                tripId, passengerId, seat,
                fromStopId, "Terminal Bogotá", 1,
                toStopId, "Terminal Medellín", 2,
                new BigDecimal("50000"), Ticket.PaymentMethod.CARD,
                null, "ADULT"
        );
        return mvc.perform(post("/api/v1/trips/{tripId}/tickets", tripId)
                .header("Authorization", "Bearer " + passengerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(ticketRequest)));
    }

    private org.springframework.test.web.servlet.ResultActions approve(String token) throws Exception {
        return mvc.perform(post("/api/v1/trips/{tripId}/overbooking/approve", tripId)
                .header("Authorization", "Bearer " + token));
    }
}
