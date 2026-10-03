package com.web.integration.userstories;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.config.CustomUserDetailsService;
import com.web.config.JwtAuthenticationFilter;
import com.web.controller.TicketController;
import com.web.dto.ticket.TicketCreateRequest;
import com.web.dto.ticket.TicketResponse;
import com.web.entity.Ticket;
import com.web.entity.User;
import com.web.repository.UserRepository;
import com.web.service.ticket.SeatHoldService;
import com.web.service.ticket.TicketService;
import com.web.util.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = TicketController.class)
@AutoConfigureMockMvc(addFilters = false)
class PassengerTicketQrTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper om;

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

    @BeforeEach
    void setUp() {

        User passenger = User.builder()
                .id(1L)
                .email("passenger@test.com")
                .name("Test Passenger")
                .role(User.Role.PASSENGER)
                .passwordHash("$2a$10$hashedpassword")
                .status(User.Status.ACTIVE)
                .build();
        when(userRepository.findByEmail("passenger@test.com")).thenReturn(Optional.of(passenger));
    }

    // Verifica que la compra devuelva un código QR válido
    @Test
    @WithMockUser(roles = "PASSENGER")
    void purchaseTicket_shouldReturnQrCode() throws Exception {
        // Given: Compra de ticket que genera QR
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
                .andExpect(jsonPath("$.qrCode").exists())
                .andExpect(jsonPath("$.qrCode").value("TKT-2025-001-ABC123"))
                .andExpect(jsonPath("$.qrCode").value(org.hamcrest.Matchers.startsWith("TKT-")));
    }

    // Verifica que al consultar ticket se recupere el QR
    @Test
    @WithMockUser(roles = "PASSENGER")
    void getTicket_shouldIncludeQrCode() throws Exception {
        // Given: Ticket con QR existente
        var response = new TicketResponse(
                1L, 1L, "Bogotá - Medellín", LocalDate.now().plusDays(1),
                LocalDateTime.now().plusDays(1).plusHours(8),
                1L, "Juan Pérez", "juan@example.com",
                10, 1L, "Bogotá", 1, 2L, "Medellín", 2,
                BigDecimal.valueOf(50000), Ticket.PaymentMethod.CARD,
                Ticket.TicketStatus.SOLD, "TKT-2025-001-ABC123", LocalDateTime.now(), null
        , null);

        when(ticketService.getTicketById(1L)).thenReturn(response);

        // When & Then
        mvc.perform(get("/api/v1/tickets/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.qrCode").exists())
                .andExpect(jsonPath("$.qrCode").value("TKT-2025-001-ABC123"));
    }

    // Verifica que la lista de tickets propios incluya QR
    @Test
    @WithMockUser(username = "passenger@test.com", roles = "PASSENGER")
    void getMyTickets_shouldIncludeQrCodes() throws Exception {
        // Given: Lista de tickets con QR
        var ticket1 = new TicketResponse(
                1L, 1L, "Bogotá - Medellín", LocalDate.now().plusDays(1),
                LocalDateTime.now().plusDays(1).plusHours(8),
                1L, "Juan Pérez", "juan@example.com",
                10, 1L, "Bogotá", 1, 2L, "Medellín", 2,
                BigDecimal.valueOf(50000), Ticket.PaymentMethod.CARD,
                Ticket.TicketStatus.SOLD, "TKT-2025-001-ABC123", LocalDateTime.now(), null
        , null);
        var ticket2 = new TicketResponse(
                2L, 2L, "Medellín - Cali", LocalDate.now().plusDays(2),
                LocalDateTime.now().plusDays(2).plusHours(8),
                1L, "Juan Pérez", "juan@example.com",
                15, 2L, "Medellín", 1, 3L, "Cali", 2,
                BigDecimal.valueOf(60000), Ticket.PaymentMethod.CASH,
                Ticket.TicketStatus.SOLD, "TKT-2025-002-XYZ456", LocalDateTime.now(), null
        , null);

        when(ticketService.getUserTickets(1L)).thenReturn(List.of(ticket1, ticket2));

        // When & Then
        mvc.perform(get("/api/v1/tickets/my-tickets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].qrCode").exists())
                .andExpect(jsonPath("$[0].qrCode").value("TKT-2025-001-ABC123"))
                .andExpect(jsonPath("$[1].qrCode").exists())
                .andExpect(jsonPath("$[1].qrCode").value("TKT-2025-002-XYZ456"));
    }
}

