package com.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.dto.ticket.TicketCancelResponse;
import com.web.dto.ticket.TicketCreateRequest;
import com.web.dto.ticket.TicketResponse;
import com.web.dto.ticket.reservations.SeatHoldRequest;
import com.web.dto.ticket.reservations.SeatHoldResponse;
import com.web.config.CustomUserDetailsService;
import com.web.entity.SeatHold;
import com.web.entity.Ticket;
import com.web.entity.User;
import com.web.exception.ResourceNotFoundException;
import com.web.repository.UserRepository;
import com.web.service.ticket.SeatHoldService;
import com.web.service.ticket.TicketService;
import com.web.util.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;

import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;


import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;


@WebMvcTest(TicketController.class)
class TicketControllerTest {

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
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private CustomUserDetailsService customUserDetailsService;

    // Verifica que un usuario autenticado pueda reservar temporalmente un asiento
    @Test
    @WithMockUser
    void holdSeat_shouldReturn201() throws Exception {
        var req = new SeatHoldRequest(1L, 1L, 2L);
        var resp = new SeatHoldResponse(1L, 1L, 10, 1L, LocalDateTime.now().plusMinutes(10), SeatHold.HoldStatus.HOLD, LocalDateTime.now(), null, null);

        when(seatHoldService.createHold(any(), any(), any())).thenReturn(resp);

        mvc.perform(post("/api/v1/trips/1/seats/10/hold")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.seatNumber").value(10));
    }

    // Verifica que un usuario autenticado pueda comprar un ticket
    @Test
    @WithMockUser
    void purchaseTicket_shouldReturn201() throws Exception {
        var req = new TicketCreateRequest(
                1L, 1L, 10, 1L, "Origin", 1, 2L, "Destination", 2,
                BigDecimal.valueOf(50000), Ticket.PaymentMethod.CASH, null, "ADULT"
        );
        var resp = new TicketResponse(
                1L, 1L, "Route", LocalDate.now(), LocalDateTime.now(),
                1L, "Passenger", "passenger@example.com",
                10, 1L, "Origin", 1, 2L, "Destination", 2,
                BigDecimal.valueOf(50000), Ticket.PaymentMethod.CASH,
                Ticket.TicketStatus.SOLD, "QR123", LocalDateTime.now(), null
        , null);

        when(ticketService.purchaseTicket(any())).thenReturn(resp);

        mvc.perform(post("/api/v1/trips/1/tickets")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.seatNumber").value(10));
    }

    // Verifica validación cuando el tripId de la URL no coincide con el del body
    @Test
    @WithMockUser
    void purchaseTicket_shouldReturn400WhenTripIdMismatch() throws Exception {
        var req = new TicketCreateRequest(
                2L, 1L, 10, 1L, "Origin", 1, 2L, "Destination", 2,
                BigDecimal.valueOf(50000), Ticket.PaymentMethod.CASH, null, "ADULT"
        );

        mvc.perform(post("/api/v1/trips/1/tickets")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    // Verifica que un usuario autenticado pueda cancelar un ticket con reembolso
    @Test
    @WithMockUser
    void cancelTicket_shouldReturn200() throws Exception {
        var resp = new TicketCancelResponse(
                1L, Ticket.TicketStatus.CANCELLED, BigDecimal.valueOf(45000), 90, "Ticket cancelado exitosamente"
        );

        when(ticketService.cancelTicket(1L)).thenReturn(resp);

        mvc.perform(post("/api/v1/tickets/1/cancel")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticketId").value(1))
                .andExpect(jsonPath("$.refundPercentage").value(90));
    }

    // Verifica que un usuario autenticado pueda consultar un ticket por ID
    @Test
    @WithMockUser
    void getTicketById_shouldReturn200() throws Exception {
        var resp = new TicketResponse(
                1L, 1L, "Route", LocalDate.now(), LocalDateTime.now(),
                1L, "Passenger", "passenger@example.com",
                10, 1L, "Origin", 1, 2L, "Destination", 2,
                BigDecimal.valueOf(50000), Ticket.PaymentMethod.CASH,
                Ticket.TicketStatus.SOLD, "QR123", LocalDateTime.now(), null
        , null);

        when(ticketService.getTicketById(1L)).thenReturn(resp);

        mvc.perform(get("/api/v1/tickets/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1));
    }

    // Verifica que retorne 404 cuando el ticket no existe
    @Test
    @WithMockUser
    void getTicketById_shouldReturn404WhenNotFound() throws Exception {
        when(ticketService.getTicketById(99L)).thenThrow(new ResourceNotFoundException("Ticket", 99L));

        mvc.perform(get("/api/v1/tickets/99"))
                .andExpect(status().isNotFound());
    }

    // Verifica que un usuario pueda consultar todos sus tickets
    @Test
    @WithMockUser(username = "user@example.com")
    void getMyTickets_shouldReturn200() throws Exception {
        var user = User.builder()
                .id(1L)
                .email("user@example.com")
                .build();
        var resp = List.of(new TicketResponse(
                1L, 1L, "Route", LocalDate.now(), LocalDateTime.now(),
                1L, "Passenger", "passenger@example.com",
                10, 1L, "Origin", 1, 2L, "Destination", 2,
                BigDecimal.valueOf(50000), Ticket.PaymentMethod.CASH,
                Ticket.TicketStatus.SOLD, "QR123", LocalDateTime.now(), null
        , null));

        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(ticketService.getUserTickets(1L)).thenReturn(resp);

        mvc.perform(get("/api/v1/tickets/my-tickets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1));
    }
}

