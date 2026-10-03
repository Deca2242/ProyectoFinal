package com.web.integration.userstories;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.dto.auth.Login.LoginRequest;
import com.web.dto.auth.Login.LoginResponse;
import com.web.dto.auth.Login.RegisterRequest;
import com.web.dto.dispatch.Assignment.AssignmentCreateRequest;
import com.web.dto.ticket.TicketCreateRequest;
import com.web.dto.ticket.TicketResponse;
import com.web.dto.trip.TripResponse;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class DriverBoardingIntegrationTest extends BaseIntegrationTest {

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

    private String driverToken;
    private Long driverId;
    private String dispatcherToken;
    private Long dispatcherId;
    private String passengerToken;
    private Long passengerId;
    private Long tripId;
    private Long fromStopId;
    private Long toStopId;
    private String ticketQrCode;

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

        // Registrar DRIVER
        RegisterRequest driverRegister = new RegisterRequest(
                "Test Driver",
                "driver@test.com",
                "1111111111",
                "password123",
                User.Role.DRIVER
        );
        createUser(driverRegister);

        LoginRequest driverLogin = new LoginRequest("driver@test.com", "password123");
        MvcResult driverLoginResult = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(driverLogin)))
                .andExpect(status().isOk())
                .andReturn();

        LoginResponse driverLoginResponse = om.readValue(driverLoginResult.getResponse().getContentAsString(), LoginResponse.class);
        driverToken = driverLoginResponse.token();
        driverId = driverLoginResponse.user().id();

        // Registrar DISPATCHER
        RegisterRequest dispatcherRegister = new RegisterRequest(
                "Test Dispatcher",
                "dispatcher@test.com",
                "2222222222",
                "password123",
                User.Role.DISPATCHER
        );
        createUser(dispatcherRegister);

        LoginRequest dispatcherLogin = new LoginRequest("dispatcher@test.com", "password123");
        MvcResult dispatcherLoginResult = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(dispatcherLogin)))
                .andExpect(status().isOk())
                .andReturn();

        LoginResponse dispatcherLoginResponse = om.readValue(dispatcherLoginResult.getResponse().getContentAsString(), LoginResponse.class);
        dispatcherToken = dispatcherLoginResponse.token();
        dispatcherId = dispatcherLoginResponse.user().id();

        // Registrar pasajero
        RegisterRequest passengerRegister = new RegisterRequest(
                "Test Passenger",
                "passenger@test.com",
                "3333333333",
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

        // Asignar viaje al conductor
        AssignmentCreateRequest assignmentRequest = new AssignmentCreateRequest(
                tripId, driverId, dispatcherId
        );
        mvc.perform(post("/api/v1/trips/{tripId}/assign", tripId)
                        .header("Authorization", "Bearer " + dispatcherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(assignmentRequest)))
                .andExpect(status().isCreated());

        // Comprar ticket para el pasajero
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
        ticketQrCode = ticketResponse.qrCode();
    }

    // Verifica validación de QR: conductor escanea QR del ticket, obtiene detalles del pasajero
    @Test
    void validateQrCode_shouldReturnTicketDetails() throws Exception {
        // Conductor escanea QR y obtiene detalles
        MvcResult qrResult = mvc.perform(get("/api/v1/tickets/qr/{qrCode}", ticketQrCode)
                        .header("Authorization", "Bearer " + driverToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.qrCode").value(ticketQrCode))
                .andExpect(jsonPath("$.seatNumber").value(1))
                .andExpect(jsonPath("$.status").value("SOLD"))
                .andReturn();

        // Verificar ticket corresponde al QR escaneado
        TicketResponse ticketResponse = om.readValue(qrResult.getResponse().getContentAsString(), TicketResponse.class);
        assertThat(ticketResponse.qrCode()).isEqualTo(ticketQrCode);
        assertThat(ticketResponse.passengerId()).isEqualTo(passengerId);
    }

    // Verifica que abrir abordaje cambia estado: despachador abre abordaje, estado cambia a BOARDING
    @Test
    void openBoarding_shouldChangeTripStatus() throws Exception {
        // Despachador abre proceso de abordaje
        mvc.perform(post("/api/v1/trips/{tripId}/boarding/open", tripId)
                        .header("Authorization", "Bearer " + dispatcherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BOARDING"));

        // Verificar estado cambió a BOARDING
        MvcResult tripResult = mvc.perform(get("/api/v1/trips/{tripId}", tripId)
                        .header("Authorization", "Bearer " + driverToken))
                .andExpect(status().isOk())
                .andReturn();

        TripResponse tripResponse = om.readValue(tripResult.getResponse().getContentAsString(), TripResponse.class);
        assertThat(tripResponse.status()).isEqualTo(com.web.entity.Trip.TripStatus.BOARDING);
    }

    // Verifica lista de pasajeros por tramo: conductor consulta pasajeros para tramo Bogotá→Medellín, solo aparece pasajero de ese tramo
    @Test
    void getPassengersBySegment_shouldReturnPassengerList() throws Exception {
        // Conductor consulta lista de pasajeros para tramo Bogotá → Medellín
        MvcResult passengersResult = mvc.perform(get("/api/v1/trips/{tripId}/passengers", tripId)
                        .param("fromStopId", fromStopId.toString())
                        .param("toStopId", toStopId.toString())
                        .header("Authorization", "Bearer " + driverToken))
                .andExpect(status().isOk())
                .andReturn();

        List<TicketResponse> passengers = om.readValue(
                passengersResult.getResponse().getContentAsString(),
                om.getTypeFactory().constructCollectionType(List.class, TicketResponse.class)
        );

        // Verificar solo aparece pasajero de ese tramo
        assertThat(passengers).hasSize(1);
        assertThat(passengers.get(0).passengerId()).isEqualTo(passengerId);
        assertThat(passengers.get(0).seatNumber()).isEqualTo(1);
        assertThat(passengers.get(0).fromStopId()).isEqualTo(fromStopId);
        assertThat(passengers.get(0).toStopId()).isEqualTo(toStopId);
    }

    // Verifica flujo completo de abordaje: despachador abre, conductor valida QR, consulta pasajeros, despachador cierra
    @Test
    void completeBoardingFlow_shouldSucceed() throws Exception {
        // Despachador abre abordaje
        mvc.perform(post("/api/v1/trips/{tripId}/boarding/open", tripId)
                        .header("Authorization", "Bearer " + dispatcherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BOARDING"));

        // Conductor valida QR del pasajero
        mvc.perform(get("/api/v1/tickets/qr/{qrCode}", ticketQrCode)
                        .header("Authorization", "Bearer " + driverToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.qrCode").value(ticketQrCode));

        // Conductor consulta lista de pasajeros del tramo
        MvcResult passengersResult = mvc.perform(get("/api/v1/trips/{tripId}/passengers", tripId)
                        .param("fromStopId", fromStopId.toString())
                        .param("toStopId", toStopId.toString())
                        .header("Authorization", "Bearer " + driverToken))
                .andExpect(status().isOk())
                .andReturn();

        List<TicketResponse> passengers = om.readValue(
                passengersResult.getResponse().getContentAsString(),
                om.getTypeFactory().constructCollectionType(List.class, TicketResponse.class)
        );
        assertThat(passengers).hasSize(1);

        // Despachador cierra proceso de abordaje
        mvc.perform(post("/api/v1/trips/{tripId}/boarding/close", tripId)
                        .header("Authorization", "Bearer " + dispatcherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BOARDING"));
    }
}
