package com.web.integration.userstories;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.dto.auth.Login.LoginRequest;
import com.web.dto.auth.Login.LoginResponse;
import com.web.dto.auth.Login.RegisterRequest;
import com.web.dto.ticket.TicketCreateRequest;
import com.web.dto.trip.SeatAvailabilityResponse;
import com.web.dto.trip.SeatStatusResponse;
import com.web.dto.trip.TripDetailResponse;
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
class DispatcherOccupancyIntegrationTest extends BaseIntegrationTest {

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

    private String dispatcherToken;
    private String passengerToken;
    private Long passengerId;
    private Long tripId;
    private Long fromStopId;
    private Long toStopId;
    private int busCapacity = 40;

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

        // Crear bus
        Bus bus = Bus.builder()
                .plate("TEST-001")
                .capacity(busCapacity)
                .amenities(new HashMap<>())
                .status(Bus.BusStatus.ACTIVE)
                .build();
        bus = busRepository.save(bus);

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

        // Registrar DISPATCHER
        RegisterRequest dispatcherRegister = new RegisterRequest(
                "Test Dispatcher",
                "dispatcher@test.com",
                "1111111111",
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

    // Verifica monitoreo de ocupación en tiempo real: consultar ocupación inicial, comprar 5 tickets, verificar actualización
    @Test
    void monitorOccupancy_shouldUpdateInRealTime() throws Exception {
        // Consultar ocupación inicial (todos disponibles)
        MvcResult initialSeatsResult = mvc.perform(get("/api/v1/trips/{tripId}/seats", tripId)
                        .param("fromStopId", fromStopId.toString())
                        .param("toStopId", toStopId.toString()))
                .andExpect(status().isOk())
                .andReturn();

        List<SeatStatusResponse> initialSeats = om.readValue(
                initialSeatsResult.getResponse().getContentAsString(), SeatAvailabilityResponse.class).seats();

        long initialOccupied = initialSeats.stream()
                .filter(seat -> !seat.available())
                .count();
        assertThat(initialOccupied).isEqualTo(0);

        // Comprar 5 tickets para ocupar asientos
        int ticketsToPurchase = 5;
        for (int i = 1; i <= ticketsToPurchase; i++) {
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

        // Verificar ocupación actualizada (5 asientos ocupados)
        MvcResult afterSeatsResult = mvc.perform(get("/api/v1/trips/{tripId}/seats", tripId)
                        .param("fromStopId", fromStopId.toString())
                        .param("toStopId", toStopId.toString()))
                .andExpect(status().isOk())
                .andReturn();

        List<SeatStatusResponse> afterSeats = om.readValue(
                afterSeatsResult.getResponse().getContentAsString(), SeatAvailabilityResponse.class).seats();

        long afterOccupied = afterSeats.stream()
                .filter(seat -> !seat.available())
                .count();
        assertThat(afterOccupied).isEqualTo(ticketsToPurchase);

        // Verificar detalle del viaje incluye % de ocupación
        MvcResult tripDetailResult = mvc.perform(get("/api/v1/trips/{tripId}", tripId)
                        .header("Authorization", "Bearer " + dispatcherToken))
                .andExpect(status().isOk())
                .andReturn();

        TripDetailResponse tripDetail = om.readValue(tripDetailResult.getResponse().getContentAsString(), TripDetailResponse.class);
        assertThat(tripDetail.soldSeats()).isEqualTo(ticketsToPurchase);
        assertThat(tripDetail.bus().capacity()).isEqualTo(busCapacity);
        assertThat(tripDetail.occupancyPercentage()).isGreaterThan(0.0);
        assertThat(tripDetail.occupancyPercentage()).isLessThanOrEqualTo(100.0);
    }

    // Verifica disponibilidad por tramo: comprar 5 tickets para tramo Bogotá→Medellín, verificar solo 5 asientos ocupados en ese tramo
    @Test
    void getSeatAvailability_shouldShowOccupancyBySegment() throws Exception {
        // Comprar 5 tickets para tramo Bogotá → Medellín
        for (int i = 1; i <= 5; i++) {
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

        // Verificar solo 5 asientos ocupados en ese tramo
        MvcResult seatsResult = mvc.perform(get("/api/v1/trips/{tripId}/seats", tripId)
                        .param("fromStopId", fromStopId.toString())
                        .param("toStopId", toStopId.toString()))
                .andExpect(status().isOk())
                .andReturn();

        List<SeatStatusResponse> seats = om.readValue(
                seatsResult.getResponse().getContentAsString(), SeatAvailabilityResponse.class).seats();

        long occupied = seats.stream()
                .filter(seat -> !seat.available())
                .count();
        assertThat(occupied).isEqualTo(5);

        long available = seats.stream()
                .filter(SeatStatusResponse::available)
                .count();
        assertThat(available).isEqualTo(busCapacity - 5);
    }
}
