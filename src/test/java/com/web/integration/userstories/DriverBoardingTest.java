package com.web.integration.userstories;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.config.CustomUserDetailsService;
import com.web.config.JwtAuthenticationFilter;
import com.web.controller.DispatchController;
import com.web.controller.TicketController;
import com.web.controller.TripController;
import com.web.dto.dispatch.Assignment.AssignmentResponse;
import com.web.dto.ticket.TicketResponse;
import com.web.dto.trip.TripResponse;
import com.web.entity.Ticket;
import com.web.entity.Trip;
import com.web.exception.ResourceNotFoundException;
import com.web.repository.UserRepository;
import com.web.service.dispatch.AssignmentService;
import com.web.service.dispatch.BoardingService;
import com.web.service.ticket.SeatHoldService;
import com.web.service.ticket.TicketService;
import com.web.service.trip.TripService;
import com.web.util.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;


import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;


import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {TicketController.class, TripController.class, DispatchController.class})
@AutoConfigureMockMvc(addFilters = false)
class DriverBoardingTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper om;

    @MockitoBean
    private TicketService ticketService;

    @MockitoBean
    private TripService tripService;

    @MockitoBean
    private BoardingService boardingService;

    @MockitoBean
    private AssignmentService assignmentService;

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

    // Verifica validación exitosa de QR para el abordaje
    @Test
    @WithMockUser(roles = {"DRIVER", "DISPATCHER", "CLERK"})
    void validateQrCode_shouldReturnTicketDetails() throws Exception {
        // Given: QR válido que retorna ticket
        var ticketResponse = new TicketResponse(
                1L, 1L, "Bogotá - Medellín", LocalDate.now().plusDays(1),
                LocalDateTime.now().plusDays(1).plusHours(8),
                1L, "Juan Pérez", "juan@example.com",
                10, 1L, "Bogotá", 1, 2L, "Medellín", 2,
                BigDecimal.valueOf(50000), Ticket.PaymentMethod.CARD,
                Ticket.TicketStatus.SOLD, "TKT-2025-001-ABC123", LocalDateTime.now(), null
        , null);

        when(ticketService.getTicketByQrCode("TKT-2025-001-ABC123")).thenReturn(ticketResponse);

        // When & Then: Validar por QR (usando endpoint de búsqueda por QR)
        mvc.perform(get("/api/v1/tickets/qr/TKT-2025-001-ABC123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.seatNumber").value(10))
                .andExpect(jsonPath("$.passengerName").value("Juan Pérez"))
                .andExpect(jsonPath("$.fromStopName").value("Bogotá"))
                .andExpect(jsonPath("$.toStopName").value("Medellín"));
    }

    // Verifica error cuando el QR escaneado no existe
    @Test
    @WithMockUser(roles = {"DRIVER", "DISPATCHER", "CLERK"})
    void validateQrCode_shouldFailWhenInvalid() throws Exception {
        // Given: QR inválido
        when(ticketService.getTicketByQrCode("INVALID-QR"))
                .thenThrow(new ResourceNotFoundException("Ticket", "INVALID-QR"));

        // When & Then
        mvc.perform(get("/api/v1/tickets/qr/INVALID-QR"))
                .andExpect(status().isNotFound());
    }

    // Verifica que el conductor obtenga pasajeros por tramo
    @Test
    @WithMockUser(roles = {"DRIVER", "DISPATCHER"})
    void getPassengersBySegment_shouldReturnList() throws Exception {
        // Given: Lista de pasajeros por tramo
        var passenger1 = new TicketResponse(
                1L, 1L, "Bogotá - Medellín", LocalDate.now().plusDays(1),
                LocalDateTime.now().plusDays(1).plusHours(8),
                1L, "Juan Pérez", "juan@example.com",
                10, 1L, "Bogotá", 1, 2L, "Medellín", 2,
                BigDecimal.valueOf(50000), Ticket.PaymentMethod.CARD,
                Ticket.TicketStatus.SOLD, "TKT-2025-001-ABC123", LocalDateTime.now(), null
        , null);
        var passenger2 = new TicketResponse(
                2L, 1L, "Bogotá - Medellín", LocalDate.now().plusDays(1),
                LocalDateTime.now().plusDays(1).plusHours(8),
                2L, "María García", "maria@example.com",
                15, 1L, "Bogotá", 1, 2L, "Medellín", 2,
                BigDecimal.valueOf(50000), Ticket.PaymentMethod.CASH,
                Ticket.TicketStatus.SOLD, "TKT-2025-002-XYZ456", LocalDateTime.now(), null
        , null);

        when(tripService.getPassengersBySegment(1L, 1L, 2L))
                .thenReturn(List.of(passenger1, passenger2));

        // When & Then
        mvc.perform(get("/api/v1/trips/1/passengers")
                        .param("fromStopId", "1")
                        .param("toStopId", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].passengerName").value("Juan Pérez"))
                .andExpect(jsonPath("$[0].seatNumber").value(10))
                .andExpect(jsonPath("$[1].passengerName").value("María García"))
                .andExpect(jsonPath("$[1].seatNumber").value(15));
    }

    // Verifica que el despachador pueda abrir el abordaje
    @Test
    @WithMockUser(roles = "DISPATCHER")
    void openBoarding_shouldChangeTripStatus() throws Exception {
        // Given: Abrir abordaje cambia estado a BOARDING
        var tripResponse = new TripResponse(
                1L, 1L, "Bogotá - Medellín", "Bogotá", "Medellín",
                1L, "ABC123", 40,
                LocalDate.now().plusDays(1), LocalDateTime.now().plusDays(1).plusHours(8), null,
                Trip.TripStatus.BOARDING, 25, 62.5
        );

        when(boardingService.openBoarding(1L)).thenReturn(tripResponse);

        // When & Then
        mvc.perform(post("/api/v1/trips/1/boarding/open"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BOARDING"));
    }
}

