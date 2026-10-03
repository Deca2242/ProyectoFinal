package com.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.dto.payment.CashCloseRequest;
import com.web.dto.payment.CashCloseResponse;
import com.web.dto.payment.PaymentConfirmRequest;
import com.web.dto.payment.PaymentResponse;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

    private void givenUser(String email, long id, String name) {
        when(userRepository.findByEmail(email))
                .thenReturn(Optional.of(User.builder().id(id).email(email).name(name).build()));
    }

    private PaymentConfirmRequest validConfirmRequest() {
        return new PaymentConfirmRequest(1L, Ticket.PaymentMethod.QR, "NEQUI-1", null, null);
    }

    private CashCloseRequest validCashCloseRequest() {
        return new CashCloseRequest(LocalDate.now(), null, BigDecimal.valueOf(100000), null);
    }

    private PaymentResponse receipt() {
        return new PaymentResponse(7L, "RCP-7", 1L, "QR123", Ticket.PaymentMethod.QR,
                BigDecimal.valueOf(50000), Ticket.PaymentStatus.PAID, "NEQUI-1", null,
                LocalDateTime.now(), "Clerk Name");
    }

    private CashCloseResponse cashClose(long id, long userId, String userName) {
        return new CashCloseResponse(id, userId, userName, LocalDate.now(),
                BigDecimal.valueOf(100000), BigDecimal.valueOf(100000), BigDecimal.ZERO,
                Map.of("CASH", BigDecimal.valueOf(100000), "TRANSFER", BigDecimal.ZERO,
                        "QR", BigDecimal.ZERO, "CARD", BigDecimal.ZERO),
                5, BigDecimal.ZERO, BigDecimal.ZERO, null, LocalDateTime.now());
    }

    // POST /payments/confirm

    // Verifica que un CLERK confirme un pago y reciba el comprobante
    @Test
    @WithMockUser(username = "clerk@example.com", roles = "CLERK")
    void confirmPayment_shouldReturn200WithReceipt() throws Exception {
        givenUser("clerk@example.com", 1L, "Clerk Name");
        when(paymentService.confirmPayment(any(), eq(1L))).thenReturn(receipt());

        mvc.perform(post("/api/v1/payments/confirm")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(validConfirmRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receiptNumber").value("RCP-7"))
                .andExpect(jsonPath("$.ticketId").value(1))
                .andExpect(jsonPath("$.paymentMethod").value("QR"))
                .andExpect(jsonPath("$.paymentStatus").value("PAID"))
                .andExpect(jsonPath("$.confirmedBy").value("Clerk Name"));
    }

    // Verifica que un DRIVER pueda cobrar al subir (el servicio valida que sea el conductor asignado)
    @Test
    @WithMockUser(username = "driver@example.com", roles = "DRIVER")
    void confirmPayment_shouldAllowDriver() throws Exception {
        givenUser("driver@example.com", 4L, "Driver Name");
        when(paymentService.confirmPayment(any(), eq(4L))).thenReturn(receipt());

        mvc.perform(post("/api/v1/payments/confirm")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(validConfirmRequest())))
                .andExpect(status().isOk());

        verify(paymentService).confirmPayment(any(PaymentConfirmRequest.class), eq(4L));
    }

    // Verifica que el 403 de un conductor no asignado se propague
    @Test
    @WithMockUser(username = "driver@example.com", roles = "DRIVER")
    void confirmPayment_shouldReturn403ForUnassignedDriver() throws Exception {
        givenUser("driver@example.com", 4L, "Driver Name");
        when(paymentService.confirmPayment(any(), eq(4L))).thenThrow(
                new BusinessException("El conductor no está asignado a este viaje", HttpStatus.FORBIDDEN, "DRIVER_NOT_ASSIGNED"));

        mvc.perform(post("/api/v1/payments/confirm")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(validConfirmRequest())))
                .andExpect(status().isForbidden());
    }

    // Verifica que se rechace una confirmación sin ticket ni método de pago
    @Test
    @WithMockUser(roles = "CLERK")
    void confirmPayment_shouldReturn400WhenInvalid() throws Exception {
        mvc.perform(post("/api/v1/payments/confirm")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transactionReference\":\"REF-1\",\"amount\":-5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.ticketId").exists())
                .andExpect(jsonPath("$.validationErrors.paymentMethod").exists())
                .andExpect(jsonPath("$.validationErrors.amount").exists());

        verifyNoInteractions(paymentService);
    }

    // Verifica que retorne 404 cuando el ticket a confirmar no existe
    @Test
    @WithMockUser(username = "clerk@example.com", roles = "CLERK")
    void confirmPayment_shouldReturn404WhenTicketNotFound() throws Exception {
        givenUser("clerk@example.com", 1L, "Clerk Name");
        when(paymentService.confirmPayment(any(), any())).thenThrow(new ResourceNotFoundException("Ticket", 1L));

        mvc.perform(post("/api/v1/payments/confirm")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(validConfirmRequest())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Ticket con id 1 no encontrado"));
    }

    // Verifica que un pago ya confirmado devuelva 409
    @Test
    @WithMockUser(username = "clerk@example.com", roles = "CLERK")
    void confirmPayment_shouldReturn409WhenAlreadyPaid() throws Exception {
        givenUser("clerk@example.com", 1L, "Clerk Name");
        when(paymentService.confirmPayment(any(), any())).thenThrow(
                new BusinessException("El pago del ticket ya fue confirmado", HttpStatus.CONFLICT, "PAYMENT_ALREADY_CONFIRMED"));

        mvc.perform(post("/api/v1/payments/confirm")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(validConfirmRequest())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("El pago del ticket ya fue confirmado"));
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

    // Verifica que un DISPATCHER no pueda confirmar pagos
    @Test
    @WithMockUser(roles = "DISPATCHER")
    void confirmPayment_shouldReturn403ForDispatcher() throws Exception {
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

    // GET /tickets/{id}/receipt

    // Verifica que el pasajero obtenga el comprobante de su ticket
    @Test
    @WithMockUser(username = "pax@example.com", roles = "PASSENGER")
    void getReceipt_shouldReturn200ForPassenger() throws Exception {
        givenUser("pax@example.com", 3L, "Pasajero");
        when(paymentService.getReceipt(1L, 3L)).thenReturn(receipt());

        mvc.perform(get("/api/v1/tickets/{id}/receipt", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receiptNumber").value("RCP-7"))
                .andExpect(jsonPath("$.transactionReference").value("NEQUI-1"));
    }

    // Verifica que un ticket sin pagar no tenga comprobante (404)
    @Test
    @WithMockUser(username = "clerk@example.com", roles = "CLERK")
    void getReceipt_shouldReturn404WhenNotPaid() throws Exception {
        givenUser("clerk@example.com", 1L, "Clerk Name");
        when(paymentService.getReceipt(1L, 1L)).thenThrow(
                new ResourceNotFoundException("El ticket 1 no tiene comprobante: el pago está pendiente"));

        mvc.perform(get("/api/v1/tickets/{id}/receipt", 1L))
                .andExpect(status().isNotFound());
    }

    // Verifica que un DRIVER no consulte comprobantes
    @Test
    @WithMockUser(username = "driver@example.com", roles = "DRIVER")
    void getReceipt_shouldReturn403ForDriver() throws Exception {
        mvc.perform(get("/api/v1/tickets/{id}/receipt", 1L))
                .andExpect(status().isForbidden());

        verifyNoInteractions(paymentService);
    }

    // POST /cash/close

    // Verifica que un CLERK pueda cerrar caja comparando efectivo reportado vs esperado
    @Test
    @WithMockUser(username = "clerk@example.com", roles = "CLERK")
    void closeCash_shouldReturn200() throws Exception {
        givenUser("clerk@example.com", 1L, "Clerk Name");
        when(paymentService.closeCash(any(), eq(1L))).thenReturn(cashClose(12L, 1L, "Clerk Name"));

        mvc.perform(post("/api/v1/cash/close")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(validCashCloseRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(12))
                .andExpect(jsonPath("$.ticketCount").value(5))
                .andExpect(jsonPath("$.totalsByMethod.CASH").value(100000));
    }

    // Antes devolvía 500: si el usuario del token ya no existe la petición no está autenticada
    @Test
    @WithMockUser(username = "clerk@example.com", roles = "CLERK")
    void closeCash_shouldReturn401WhenUserNotFound() throws Exception {
        when(userRepository.findByEmail("clerk@example.com")).thenReturn(Optional.empty());

        mvc.perform(post("/api/v1/cash/close")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(validCashCloseRequest())))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(paymentService);
    }

    // Verifica que un DRIVER pueda cerrar caja y que se use el id del usuario autenticado
    @Test
    @WithMockUser(username = "driver@example.com", roles = "DRIVER")
    void closeCash_shouldReturn200ForDriverUsingAuthenticatedUserId() throws Exception {
        givenUser("driver@example.com", 4L, "Driver Name");
        when(paymentService.closeCash(any(), eq(4L))).thenReturn(cashClose(13L, 4L, "Driver Name"));

        // El body trae un userId de otro usuario (99): se ignora y prevalece el autenticado
        mvc.perform(post("/api/v1/cash/close")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":99,\"date\":\"" + LocalDate.now() + "\",\"actualAmount\":50000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userName").value("Driver Name"));

        verify(paymentService).closeCash(any(CashCloseRequest.class), eq(4L));
    }

    // Verifica que un segundo cierre del mismo día devuelva 409
    @Test
    @WithMockUser(username = "clerk@example.com", roles = "CLERK")
    void closeCash_shouldReturn409WhenAlreadyClosed() throws Exception {
        givenUser("clerk@example.com", 1L, "Clerk Name");
        when(paymentService.closeCash(any(), eq(1L))).thenThrow(
                new BusinessException("La caja ya fue cerrada", HttpStatus.CONFLICT, "CASH_ALREADY_CLOSED"));

        mvc.perform(post("/api/v1/cash/close")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(validCashCloseRequest())))
                .andExpect(status().isConflict());
    }

    // Verifica que se rechace un cierre de caja sin fecha ni monto real
    @Test
    @WithMockUser(username = "clerk@example.com", roles = "CLERK")
    void closeCash_shouldReturn400WhenInvalid() throws Exception {
        mvc.perform(post("/api/v1/cash/close")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"notes\":\"sin datos\"}"))
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
                        .content("{\"date\":\"ayer\",\"actualAmount\":1000}"))
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
                        .content(om.writeValueAsString(validCashCloseRequest())))
                .andExpect(status().isForbidden());

        verifyNoInteractions(paymentService, userRepository);
    }

    // Verifica que sin autenticación no se pueda cerrar caja
    @Test
    void closeCash_shouldReturn401WhenAnonymous() throws Exception {
        mvc.perform(post("/api/v1/cash/close")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(validCashCloseRequest())))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(paymentService, userRepository);
    }

    // GET /cash/closes

    // Verifica que ADMIN liste los cierres de una fecha
    @Test
    @WithMockUser(username = "admin@example.com", roles = "ADMIN")
    void getCashCloses_shouldReturn200ForAdmin() throws Exception {
        givenUser("admin@example.com", 9L, "Admin");
        LocalDate date = LocalDate.of(2026, 3, 15);
        when(paymentService.getCashCloses(date, 9L))
                .thenReturn(List.of(cashClose(1L, 1L, "Clerk Name"), cashClose(2L, 4L, "Driver Name")));

        mvc.perform(get("/api/v1/cash/closes").param("date", "2026-03-15"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].userName").value("Driver Name"));
    }

    // Verifica que un CLERK liste sus cierres sin fecha
    @Test
    @WithMockUser(username = "clerk@example.com", roles = "CLERK")
    void getCashCloses_shouldReturnOwnForClerk() throws Exception {
        givenUser("clerk@example.com", 1L, "Clerk Name");
        when(paymentService.getCashCloses(null, 1L)).thenReturn(List.of(cashClose(1L, 1L, "Clerk Name")));

        mvc.perform(get("/api/v1/cash/closes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].userId").value(1));
    }

    // Verifica que un PASSENGER no vea cierres de caja
    @Test
    @WithMockUser(username = "pax@example.com", roles = "PASSENGER")
    void getCashCloses_shouldReturn403ForPassenger() throws Exception {
        mvc.perform(get("/api/v1/cash/closes"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(paymentService);
    }
}
