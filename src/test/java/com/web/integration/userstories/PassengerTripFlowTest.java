package com.web.integration.userstories;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.config.CustomUserDetailsService;
import com.web.config.JwtAuthenticationFilter;
import com.web.controller.TicketController;
import com.web.controller.TripController;
import com.web.dto.ticket.TicketCreateRequest;
import com.web.dto.ticket.TicketResponse;
import com.web.dto.ticket.reservations.SeatHoldRequest;
import com.web.dto.ticket.reservations.SeatHoldResponse;
import com.web.dto.trip.SeatStatusResponse;
import com.web.dto.trip.TripResponse;
import com.web.entity.SeatHold;
import com.web.entity.Ticket;
import com.web.exception.BusinessException;
import com.web.exception.OverbookingNotAllowedException;
import com.web.exception.SeatNotAvailableException;
import com.web.repository.UserRepository;
import com.web.service.ticket.SeatHoldService;
import com.web.service.ticket.TicketService;
import com.web.service.trip.TripService;
import com.web.util.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {TripController.class, TicketController.class})
@AutoConfigureMockMvc(addFilters = false)
class PassengerTripFlowTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper om;

    @MockitoBean
    private TripService tripService;

    @MockitoBean
    private TicketService ticketService;

    @MockitoBean
    private SeatHoldService seatHoldService;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private CustomUserDetailsService customUserDetailsService;

    // Verifica que el pasajero pueda buscar viajes con cupos
    @Test
    void searchTrips_shouldReturnAvailableTrips() throws Exception {

        // Given: Viajes disponibles con asientos libres
        var trip1 = new TripResponse(
                1L, 1L, "Bogotá - Medellín", "Bogotá", "Medellín",
                1L, "ABC123", 40,
                LocalDate.now().plusDays(1), LocalDateTime.now().plusDays(1).plusHours(8), null,
                com.web.entity.Trip.TripStatus.SCHEDULED, 25, 62.5
        );
        var trip2 = new TripResponse(
                2L, 1L, "Bogotá - Medellín", "Bogotá", "Medellín",
                2L, "XYZ789", 45,
                LocalDate.now().plusDays(1), LocalDateTime.now().plusDays(1).plusHours(10), null,
                com.web.entity.Trip.TripStatus.SCHEDULED, 30, 66.7
        );

        when(tripService.searchTrips(1L, LocalDate.now().plusDays(1)))
                .thenReturn(List.of(trip1, trip2));

        // When & Then
        mvc.perform(get("/api/v1/trips")
                        .param("routeId", "1")
                        .param("date", LocalDate.now().plusDays(1).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].soldSeats").value(25))
                .andExpect(jsonPath("$[0].busCapacity").value(40))
                .andExpect(jsonPath("$[1].id").value(2))
                .andExpect(jsonPath("$[1].soldSeats").value(30))
                .andExpect(jsonPath("$[1].busCapacity").value(45));
    }

    // Verifica que el pasajero pueda ver asientos disponibles por tramo
    @Test
    void getSeatAvailability_shouldReturnAvailableSeats() throws Exception {
        // Este endpoint es público, no requiere autenticación
        // Given: Asientos disponibles para un tramo específico
        var seats = List.of(
                new SeatStatusResponse(1, true, "AVAILABLE"),
                new SeatStatusResponse(2, false, "OCCUPIED"),
                new SeatStatusResponse(3, true, "AVAILABLE"),
                new SeatStatusResponse(4, true, "AVAILABLE")
        );

        when(tripService.getSeatAvailability(1L, 1L, 2L)).thenReturn(seats);

        // When & Then
        mvc.perform(get("/api/v1/trips/1/seats")
                        .param("fromStopId", "1")
                        .param("toStopId", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].seatNumber").value(1))
                .andExpect(jsonPath("$[0].available").value(true))
                .andExpect(jsonPath("$[1].seatNumber").value(2))
                .andExpect(jsonPath("$[1].available").value(false))
                .andExpect(jsonPath("$[2].seatNumber").value(3))
                .andExpect(jsonPath("$[2].available").value(true));
    }

    // Verifica que un pasajero reserve un asiento por 10 minutos
    @Test
    @WithMockUser(roles = "PASSENGER")
    void holdSeat_shouldReserveFor10Minutes() throws Exception {
        // Given: Hold de asiento por 10 minutos
        var request = new SeatHoldRequest(1L, 1L, 2L);
        var expiresAt = LocalDateTime.now().plusMinutes(10);
        var response = new SeatHoldResponse(
                1L, 1L, 10, 1L, expiresAt, SeatHold.HoldStatus.HOLD, LocalDateTime.now()
        , null, null);

        when(seatHoldService.createHold(eq(1L), eq(10), any(SeatHoldRequest.class)))
                .thenReturn(response);

        // When & Then
        mvc.perform(post("/api/v1/trips/1/seats/10/hold")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.tripId").value(1))
                .andExpect(jsonPath("$.seatNumber").value(10))
                .andExpect(jsonPath("$.status").value("HOLD"));
    }

    // Verifica compra exitosa de ticket con segmento válido
    @Test
    @WithMockUser(roles = "PASSENGER")
    void purchaseTicket_shouldSucceedWithValidSegment() throws Exception {
        // Given: Compra de ticket con tramo válido
        var request = new TicketCreateRequest(
                1L, 1L, 10, 1L, "Bogotá", 1, 2L, "Medellín", 2,
                BigDecimal.valueOf(50000), Ticket.PaymentMethod.CARD, null, "ADULT"
        );
        var response = new TicketResponse(
                1L, 1L, "Bogotá - Medellín", LocalDate.now().plusDays(1),
                LocalDateTime.now().plusDays(1).plusHours(8),
                1L, "Juan Pérez", "juan@example.com",
                10, 1L, "Bogotá", 1, 2L, "Medellín", 2,
                BigDecimal.valueOf(50000), Ticket.PaymentMethod.CARD,
                Ticket.TicketStatus.SOLD, "TKT-2025-001-ABC123", LocalDateTime.now(), null
        , null);

        when(ticketService.purchaseTicket(any(TicketCreateRequest.class))).thenReturn(response);

        // When & Then
        mvc.perform(post("/api/v1/trips/1/tickets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.seatNumber").value(10))
                .andExpect(jsonPath("$.status").value("SOLD"))
                .andExpect(jsonPath("$.qrCode").value("TKT-2025-001-ABC123"));
    }

    // Verifica error cuando el asiento ya está ocupado
    @Test
    @WithMockUser(roles = "PASSENGER")
    void purchaseTicket_shouldFailWhenSeatOccupied() throws Exception {
        // Given: Asiento ya ocupado en el tramo
        var request = new TicketCreateRequest(
                1L, 1L, 10, 1L, "Bogotá", 1, 2L, "Medellín", 2,
                BigDecimal.valueOf(50000), Ticket.PaymentMethod.CARD, null, "ADULT"
        );

        when(ticketService.purchaseTicket(any(TicketCreateRequest.class)))
                .thenThrow(new SeatNotAvailableException("El asiento 10 ya está ocupado en el tramo Bogotá - Medellín"));

        // When & Then
        mvc.perform(post("/api/v1/trips/1/tickets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("El asiento 10 ya está ocupado en el tramo Bogotá - Medellín"));
    }

    // Verifica bloqueo por superar el límite de overbooking
    @Test
    @WithMockUser(roles = "PASSENGER")
    void purchaseTicket_shouldFailWhenOverbookingLimit() throws Exception {
        // Given: Límite de overbooking excedido
        var request = new TicketCreateRequest(
                1L, 1L, 10, 1L, "Bogotá", 1, 2L, "Medellín", 2,
                BigDecimal.valueOf(50000), Ticket.PaymentMethod.CARD, null, "ADULT"
        );

        when(ticketService.purchaseTicket(any(TicketCreateRequest.class)))
                .thenThrow(new OverbookingNotAllowedException(
                        "El viaje ha alcanzado el límite de overbooking permitido (10.0%). Ocupación actual: 105.0%"));

        // When & Then
        mvc.perform(post("/api/v1/trips/1/tickets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("overbooking")));
    }
}

