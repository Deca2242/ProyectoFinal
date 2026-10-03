package com.web.integration.userstories;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.config.CustomUserDetailsService;
import com.web.config.JwtAuthenticationFilter;
import com.web.controller.TicketController;
import com.web.dto.ticket.TicketCancelResponse;
import com.web.entity.Ticket;
import com.web.exception.BusinessException;
import com.web.repository.UserRepository;
import com.web.service.ticket.SeatHoldService;
import com.web.service.ticket.TicketService;
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

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = TicketController.class)
@AutoConfigureMockMvc(addFilters = false)
class PassengerCancellationTest {

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

    // Verifica reembolso del 80% cuando faltan al menos 48h
    @Test
    @WithMockUser(roles = "PASSENGER")
    void cancelTicket_48HoursBefore_shouldRefund80Percent() throws Exception {
        // Given: Ticket cancelado 48+ horas antes
        var response = new TicketCancelResponse(
                1L, Ticket.TicketStatus.CANCELLED,
                BigDecimal.valueOf(40000), // 80% de 50000
                80, "Ticket cancelado exitosamente"
        );

        when(ticketService.cancelTicket(1L)).thenReturn(response);

        // When & Then
        mvc.perform(post("/api/v1/tickets/1/cancel")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticketId").value(1))
                .andExpect(jsonPath("$.refundAmount").value(40000))
                .andExpect(jsonPath("$.refundPercentage").value(80))
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    // Verifica reembolso del 50% para cancelaciones 24-48h
    @Test
    @WithMockUser(roles = "PASSENGER")
    void cancelTicket_24HoursBefore_shouldRefund50Percent() throws Exception {
        // Given: Ticket cancelado 24+ horas antes
        var response = new TicketCancelResponse(
                1L, Ticket.TicketStatus.CANCELLED,
                BigDecimal.valueOf(25000), // 50% de 50000
                50, "Ticket cancelado exitosamente"
        );

        when(ticketService.cancelTicket(1L)).thenReturn(response);

        // When & Then
        mvc.perform(post("/api/v1/tickets/1/cancel")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refundAmount").value(25000))
                .andExpect(jsonPath("$.refundPercentage").value(50));
    }

    // Verifica reembolso del 30% para cancelaciones 12-24h
    @Test
    @WithMockUser(roles = "PASSENGER")
    void cancelTicket_12HoursBefore_shouldRefund30Percent() throws Exception {
        // Given: Ticket cancelado 12+ horas antes
        var response = new TicketCancelResponse(
                1L, Ticket.TicketStatus.CANCELLED,
                BigDecimal.valueOf(15000), // 30% de 50000
                30, "Ticket cancelado exitosamente"
        );

        when(ticketService.cancelTicket(1L)).thenReturn(response);

        // When & Then
        mvc.perform(post("/api/v1/tickets/1/cancel")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refundAmount").value(15000))
                .andExpect(jsonPath("$.refundPercentage").value(30));
    }

    // Verifica reembolso del 10% cuando faltan entre 6 y 12h
    @Test
    @WithMockUser(roles = "PASSENGER")
    void cancelTicket_6HoursBefore_shouldRefund10Percent() throws Exception {
        // Given: Ticket cancelado 6+ horas antes
        var response = new TicketCancelResponse(
                1L, Ticket.TicketStatus.CANCELLED,
                BigDecimal.valueOf(5000), // 10% de 50000
                10, "Ticket cancelado exitosamente"
        );

        when(ticketService.cancelTicket(1L)).thenReturn(response);

        // When & Then
        mvc.perform(post("/api/v1/tickets/1/cancel")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refundAmount").value(5000))
                .andExpect(jsonPath("$.refundPercentage").value(10));
    }

    // Verifica reembolso 0% cuando faltan menos de 6h
    @Test
    @WithMockUser(roles = "PASSENGER")
    void cancelTicket_Less6Hours_shouldRefund0Percent() throws Exception {
        // Given: Ticket cancelado menos de 6 horas antes
        var response = new TicketCancelResponse(
                1L, Ticket.TicketStatus.CANCELLED,
                BigDecimal.ZERO, // 0% de reembolso
                0, "Ticket cancelado exitosamente"
        );

        when(ticketService.cancelTicket(1L)).thenReturn(response);

        // When & Then
        mvc.perform(post("/api/v1/tickets/1/cancel")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refundAmount").value(0))
                .andExpect(jsonPath("$.refundPercentage").value(0));
    }

    // Verifica error al cancelar un ticket que ya está cancelado
    @Test
    @WithMockUser(roles = "PASSENGER")
    void cancelTicket_shouldFailWhenAlreadyCancelled() throws Exception {
        // Given: Ticket ya cancelado
        when(ticketService.cancelTicket(1L))
                .thenThrow(new BusinessException("El ticket ya está cancelado o es no-show",
                        org.springframework.http.HttpStatus.BAD_REQUEST, "TICKET_ALREADY_CANCELLED"));

        // When & Then
        mvc.perform(post("/api/v1/tickets/1/cancel")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("cancelado")));
    }
}

