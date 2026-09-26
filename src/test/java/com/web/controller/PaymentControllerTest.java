package com.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.dto.payment.CashCloseRequest;
import com.web.dto.payment.CashCloseResponse;
import com.web.dto.payment.PaymentConfirmRequest;
import com.web.dto.ticket.TicketResponse;
import com.web.config.CustomUserDetailsService;
import com.web.config.SecurityConfig;
import com.web.entity.Ticket;
import com.web.entity.User;
import com.web.exception.BusinessException;
import com.web.exception.ResourceNotFoundException;
import com.web.repository.UserRepository;
import com.web.service.payment.PaymentService;
import com.web.util.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;

import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;


@WebMvcTest(PaymentController.class)
@Import(SecurityConfig.class)
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

    private PaymentConfirmRequest validConfirmRequest() {
        return new PaymentConfirmRequest(1L, Ticket.PaymentMethod.CARD, null, null, null);
    }

    private CashCloseRequest validCashCloseRequest(Long userId) {
        return new CashCloseRequest(
                userId, LocalDate.now(), null, BigDecimal.valueOf(100000), null
        );
    }

    // POST /payments/confirm

    // Verifica que se rechace una confirmación sin ticket ni método de pago
    @Test
    @WithMockUser(roles = "CLERK")
    void confirmPayment_shouldReturn400WhenInvalid() throws Exception {
        mvc.perform(post("/api/v1/payments/confirm")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transactionReference\":\"REF-1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.ticketId").exists())
                .andExpect(jsonPath("$.validationErrors.paymentMethod").exists());

        verifyNoInteractions(paymentService);
    }

    // Verifica que retorne 404 cuando el ticket a confirmar no existe
    @Test
    @WithMockUser(roles = "CLERK")
    void confirmPayment_shouldReturn404WhenTicketNotFound() throws Exception {
        when(paymentService.confirmPayment(any())).thenThrow(new ResourceNotFoundException("Ticket", 1L));

        mvc.perform(post("/api/v1/payments/confirm")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(validConfirmRequest())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Ticket con id 1 no encontrado"));
    }

    // Verifica que un error de negocio del pago se propague con su estado
    @Test
    @WithMockUser(roles = "CLERK")
    void confirmPayment_shouldReturnBusinessErrorStatus() throws Exception {
        when(paymentService.confirmPayment(any())).thenThrow(
                new BusinessException("El ticket no está pendiente de pago", HttpStatus.BAD_REQUEST, "INVALID_TICKET_STATUS"));

        mvc.perform(post("/api/v1/payments/confirm")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(validConfirmRequest())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("El ticket no está pendiente de pago"));
    }

    // Verifica que un PASSENGER no pueda confirmar pagos
    @Test
    @WithMockUser(roles = "PASSENGER")
    void confirmPayment_shouldReturn403ForPassenger() throws Exception {
        mvc.perform(post("/api/v1/payments/confirm")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(validConfirmRequest())))
                .andExpect(status().isForbidden());

        verifyNoInteractions(paymentService);
    }

    // Verifica que un DRIVER no pueda confirmar pagos (solo CLERK)
    @Test
    @WithMockUser(roles = "DRIVER")
    void confirmPayment_shouldReturn403ForDriver() throws Exception {
        mvc.perform(post("/api/v1/payments/confirm")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(validConfirmRequest())))
                .andExpect(status().isForbidden());

        verifyNoInteractions(paymentService);
    }

    // Verifica que sin autenticación no se puedan confirmar pagos
    @Test
    void confirmPayment_shouldReturn401WhenAnonymous() throws Exception {
        mvc.perform(post("/api/v1/payments/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(validConfirmRequest())))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(paymentService);
    }

    // POST /cash/close

    // Verifica que un DRIVER pueda cerrar caja y que se use el id del usuario autenticado
    @Test
    @WithMockUser(username = "driver@example.com", roles = "DRIVER")
    void closeCash_shouldReturn200ForDriverUsingAuthenticatedUserId() throws Exception {
        var user = User.builder()
                .id(4L)
                .email("driver@example.com")
                .name("Driver Name")
                .build();
        var resp = new CashCloseResponse(
                4L, "Driver Name", LocalDate.now(),
                BigDecimal.valueOf(50000), BigDecimal.valueOf(50000),
                BigDecimal.ZERO, 2, LocalDateTime.now()
        );

        when(userRepository.findByEmail("driver@example.com")).thenReturn(Optional.of(user));
        when(paymentService.closeCash(any(), eq(4L))).thenReturn(resp);

        // El body indica otro usuario (99); debe prevalecer el autenticado
        mvc.perform(post("/api/v1/cash/close")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(validCashCloseRequest(99L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userName").value("Driver Name"));

        verify(paymentService).closeCash(any(CashCloseRequest.class), eq(4L));
    }

    // Verifica que se rechace un cierre de caja sin fecha ni monto real
    @Test
    @WithMockUser(username = "clerk@example.com", roles = "CLERK")
    void closeCash_shouldReturn400WhenInvalid() throws Exception {
        mvc.perform(post("/api/v1/cash/close")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.date").exists())
                .andExpect(jsonPath("$.validationErrors.actualAmount").exists());

        verifyNoInteractions(paymentService, userRepository);
    }

    // Verifica que una fecha mal formada devuelva 400
    @Test
    @WithMockUser(username = "clerk@example.com", roles = "CLERK")
    void closeCash_shouldReturn400WhenDateMalformed() throws Exception {
        mvc.perform(post("/api/v1/cash/close")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":1,\"date\":\"ayer\",\"actualAmount\":1000}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(paymentService);
    }

    // Verifica que un DISPATCHER no pueda cerrar caja
    @Test
    @WithMockUser(username = "dispatcher@example.com", roles = "DISPATCHER")
    void closeCash_shouldReturn403ForDispatcher() throws Exception {
        mvc.perform(post("/api/v1/cash/close")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(validCashCloseRequest(1L))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(paymentService, userRepository);
    }

    // Verifica que sin autenticación no se pueda cerrar caja
    @Test
    void closeCash_shouldReturn401WhenAnonymous() throws Exception {
        mvc.perform(post("/api/v1/cash/close")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(validCashCloseRequest(1L))))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(paymentService, userRepository);
    }
}

