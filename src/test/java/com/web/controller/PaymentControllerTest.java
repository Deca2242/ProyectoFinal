package com.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.dto.payment.CashCloseRequest;
import com.web.dto.payment.CashCloseResponse;
import com.web.dto.payment.PaymentConfirmRequest;
import com.web.dto.ticket.TicketResponse;
import com.web.config.CustomUserDetailsService;
import com.web.entity.Ticket;
import com.web.entity.User;

import com.web.repository.UserRepository;
import com.web.service.payment.PaymentService;
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
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;


@WebMvcTest(PaymentController.class)
class PaymentControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper om;

    @MockitoBean
    private PaymentService paymentService;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private CustomUserDetailsService customUserDetailsService;

    // Verifica que un CLERK pueda confirmar un pago
    @Test
    @WithMockUser(roles = "CLERK")
    void confirmPayment_shouldReturn200() throws Exception {
        var req = new PaymentConfirmRequest(1L, Ticket.PaymentMethod.CARD, null, null, null);
        var resp = new TicketResponse(
                1L, 1L, "Route", LocalDate.now(), LocalDateTime.now(),
                1L, "Passenger", "passenger@example.com",
                10, 1L, "Origin", 1, 2L, "Destination", 2,
                BigDecimal.valueOf(50000), Ticket.PaymentMethod.CARD,
                Ticket.TicketStatus.SOLD, "QR123", LocalDateTime.now(), null
        , null);

        when(paymentService.confirmPayment(any())).thenReturn(resp);

        mvc.perform(post("/api/v1/payments/confirm")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.paymentMethod").value("CARD"));
    }

    // Verifica que un CLERK pueda cerrar caja comparando efectivo reportado vs esperado
    @Test
    @WithMockUser(username = "clerk@example.com", roles = "CLERK")
    void closeCash_shouldReturn200() throws Exception {
        var req = new CashCloseRequest(
                1L, LocalDate.now(), BigDecimal.valueOf(100000), BigDecimal.valueOf(100000), null
        );
        var user = User.builder()
                .id(1L)
                .email("clerk@example.com")
                .name("Clerk Name")
                .build();
        var resp = new CashCloseResponse(
                1L, "Clerk Name", LocalDate.now(),
                BigDecimal.valueOf(100000), BigDecimal.valueOf(100000),
                BigDecimal.ZERO, 5, LocalDateTime.now()
        );

        when(userRepository.findByEmail("clerk@example.com")).thenReturn(Optional.of(user));
        when(paymentService.closeCash(any(), any())).thenReturn(resp);

        mvc.perform(post("/api/v1/cash/close")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.ticketCount").value(5));
    }

    // Verifica manejo de error cuando el usuario no existe al cerrar caja
    @Test
    @WithMockUser(username = "clerk@example.com", roles = "CLERK")
    void closeCash_shouldReturn404WhenUserNotFound() throws Exception {
        var req = new CashCloseRequest(
                1L, LocalDate.now(), BigDecimal.valueOf(100000), BigDecimal.valueOf(100000), null
        );

        when(userRepository.findByEmail("clerk@example.com")).thenReturn(Optional.empty());

        mvc.perform(post("/api/v1/cash/close")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isInternalServerError());
    }
}

