package com.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.dto.ticket.TicketCancelResponse;
import com.web.dto.ticket.TicketCreateRequest;
import com.web.dto.ticket.TicketResponse;
import com.web.dto.ticket.reservations.SeatHoldRequest;
import com.web.dto.ticket.reservations.SeatHoldResponse;
import com.web.config.CustomUserDetailsService;
import com.web.config.SecurityConfig;
import com.web.entity.SeatHold;
import com.web.entity.Ticket;
import com.web.entity.User;
import com.web.exception.InvalidSegmentException;
import com.web.exception.InvalidStateTransitionException;
import com.web.exception.OverbookingNotAllowedException;
import com.web.exception.ResourceNotFoundException;
import com.web.exception.SeatNotAvailableException;
import com.web.repository.UserRepository;
import com.web.service.ticket.SeatHoldService;
import com.web.service.ticket.TicketService;
import com.web.util.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;

import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;


import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;


@WebMvcTest(TicketController.class)
@Import(SecurityConfig.class)
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

    private TicketCreateRequest validPurchaseRequest(Long tripId) {
        return new TicketCreateRequest(
                tripId, 1L, 10, 1L, "Origin", 1, 2L, "Destination", 2,
                BigDecimal.valueOf(50000), Ticket.PaymentMethod.CASH, null, "ADULT"
        );
    }

    private TicketResponse ticket() {
        return new TicketResponse(
                1L, 1L, "Route", LocalDate.now(), LocalDateTime.now(),
                1L, "Passenger", "passenger@example.com",
                10, 1L, "Origin", 1, 2L, "Destination", 2,
                BigDecimal.valueOf(50000), Ticket.PaymentMethod.CASH,
                Ticket.TicketStatus.SOLD, "QR123", LocalDateTime.now(), null
        );
    }

    // POST /trips/{tripId}/tickets

    // Verifica que el servicio reciba la petición con el tripId de la URL
    @Test
    @WithMockUser
    void purchaseTicket_shouldPassRequestWithUrlTripIdToService() throws Exception {
        when(ticketService.purchaseTicket(any())).thenReturn(ticket());

        mvc.perform(post("/api/v1/trips/3/tickets")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(validPurchaseRequest(3L))))
                .andExpect(status().isCreated());

        ArgumentCaptor<TicketCreateRequest> captor = ArgumentCaptor.forClass(TicketCreateRequest.class);
        verify(ticketService).purchaseTicket(captor.capture());
        assertThat(captor.getValue().tripId()).isEqualTo(3L);
        assertThat(captor.getValue().seatNumber()).isEqualTo(10);
        assertThat(captor.getValue().passengerType()).isEqualTo("ADULT");
    }

    // Verifica que se rechace una compra sin asiento ni precio
    @Test
    @WithMockUser
    void purchaseTicket_shouldReturn400WhenInvalid() throws Exception {
        var req = new TicketCreateRequest(
                1L, 1L, null, 1L, "Origin", 1, 2L, "Destination", 2,
                null, Ticket.PaymentMethod.CASH, null, null
        );

        mvc.perform(post("/api/v1/trips/1/tickets")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.seatNumber").exists())
                .andExpect(jsonPath("$.validationErrors.price").exists());

        verifyNoInteractions(ticketService);
    }

    // Verifica que un método de pago inexistente devuelva 400
    @Test
    @WithMockUser
    void purchaseTicket_shouldReturn400WhenPaymentMethodUnknown() throws Exception {
        String body = om.writeValueAsString(validPurchaseRequest(1L)).replace("\"CASH\"", "\"BITCOIN\"");

        mvc.perform(post("/api/v1/trips/1/tickets")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(ticketService);
    }

    // Verifica que un asiento ocupado devuelva 409
    @Test
    @WithMockUser
    void purchaseTicket_shouldReturn409WhenSeatNotAvailable() throws Exception {
        when(ticketService.purchaseTicket(any())).thenThrow(new SeatNotAvailableException(10, 1L));

        mvc.perform(post("/api/v1/trips/1/tickets")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(validPurchaseRequest(1L))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("El asiento 10 no está disponible para el viaje 1"));
    }

    // Verifica que un tramo inválido devuelva 400
    @Test
    @WithMockUser
    void purchaseTicket_shouldReturn400WhenSegmentInvalid() throws Exception {
        when(ticketService.purchaseTicket(any())).thenThrow(new InvalidSegmentException("Destino", "Origen"));

        mvc.perform(post("/api/v1/trips/1/tickets")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(validPurchaseRequest(1L))))
                .andExpect(status().isBadRequest());
    }

    // Verifica que el overbooking se traduzca con el estado definido por la propia excepción
    @Test
    @WithMockUser
    void purchaseTicket_shouldUseOverbookingExceptionStatus() throws Exception {
        OverbookingNotAllowedException ex = new OverbookingNotAllowedException();
        when(ticketService.purchaseTicket(any())).thenThrow(ex);

        mvc.perform(post("/api/v1/trips/1/tickets")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(validPurchaseRequest(1L))))
                .andExpect(status().is(ex.getStatus().value()))
                .andExpect(jsonPath("$.message").value(ex.getMessage()));
    }

    // Verifica que retorne 404 cuando el viaje no existe
    @Test
    @WithMockUser
    void purchaseTicket_shouldReturn404WhenTripNotFound() throws Exception {
        when(ticketService.purchaseTicket(any())).thenThrow(new ResourceNotFoundException("Viaje", 1L));

        mvc.perform(post("/api/v1/trips/1/tickets")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(validPurchaseRequest(1L))))
                .andExpect(status().isNotFound());
    }

    // Verifica que sin autenticación no se puedan comprar tickets
    @Test
    void purchaseTicket_shouldReturn401WhenAnonymous() throws Exception {
        mvc.perform(post("/api/v1/trips/1/tickets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(validPurchaseRequest(1L))))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(ticketService);
    }

    // POST /tickets/{id}/cancel

    // Verifica que retorne 404 al cancelar un ticket inexistente
    @Test
    @WithMockUser
    void cancelTicket_shouldReturn404WhenNotFound() throws Exception {
        when(ticketService.cancelTicket(99L)).thenThrow(new ResourceNotFoundException("Ticket", 99L));

        mvc.perform(post("/api/v1/tickets/99/cancel")
                        .with(csrf()))
                .andExpect(status().isNotFound());
    }

    // Verifica que cancelar un ticket ya cancelado devuelva 422
    @Test
    @WithMockUser
    void cancelTicket_shouldReturn422WhenAlreadyCancelled() throws Exception {
        when(ticketService.cancelTicket(1L)).thenThrow(new InvalidStateTransitionException("CANCELLED", "CANCELLED"));

        mvc.perform(post("/api/v1/tickets/1/cancel")
                        .with(csrf()))
                .andExpect(status().isUnprocessableEntity());
    }

    // Verifica que sin autenticación no se puedan cancelar tickets
    @Test
    void cancelTicket_shouldReturn401WhenAnonymous() throws Exception {
        mvc.perform(post("/api/v1/tickets/1/cancel"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(ticketService);
    }

    // GET /tickets/{id}

    // Verifica que un id no numérico devuelva 400
    @Test
    @WithMockUser
    void getTicketById_shouldReturn400WhenIdIsNotNumeric() throws Exception {
        mvc.perform(get("/api/v1/tickets/abc"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(ticketService);
    }

    // Verifica que sin autenticación no se pueda consultar un ticket
    @Test
    void getTicketById_shouldReturn401WhenAnonymous() throws Exception {
        mvc.perform(get("/api/v1/tickets/1"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(ticketService);
    }

    // GET /tickets/qr/{qrCode}

    // Verifica que un DRIVER pueda consultar un ticket por su QR
    @Test
    @WithMockUser(roles = "DRIVER")
    void getTicketByQrCode_shouldReturn200ForDriver() throws Exception {
        when(ticketService.getTicketByQrCode("QR123")).thenReturn(ticket());

        mvc.perform(get("/api/v1/tickets/qr/QR123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.qrCode").value("QR123"));
    }

    // Verifica que un CLERK pueda consultar un ticket por su QR
    @Test
    @WithMockUser(roles = "CLERK")
    void getTicketByQrCode_shouldReturn200ForClerk() throws Exception {
        when(ticketService.getTicketByQrCode("QR123")).thenReturn(ticket());

        mvc.perform(get("/api/v1/tickets/qr/QR123"))
                .andExpect(status().isOk());
    }

    // Verifica que retorne 404 cuando el QR no corresponde a ningún ticket
    @Test
    @WithMockUser(roles = "DISPATCHER")
    void getTicketByQrCode_shouldReturn404WhenNotFound() throws Exception {
        when(ticketService.getTicketByQrCode("NOPE")).thenThrow(new ResourceNotFoundException("Ticket", "NOPE"));

        mvc.perform(get("/api/v1/tickets/qr/NOPE"))
                .andExpect(status().isNotFound());
    }

    // Verifica que un PASSENGER no pueda consultar tickets por QR
    @Test
    @WithMockUser(roles = "PASSENGER")
    void getTicketByQrCode_shouldReturn403ForPassenger() throws Exception {
        mvc.perform(get("/api/v1/tickets/qr/QR123"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(ticketService);
    }

    // GET /tickets/my-tickets

    // Verifica que sin autenticación no se consulten los tickets propios
    @Test
    void getMyTickets_shouldReturn401WhenAnonymous() throws Exception {
        mvc.perform(get("/api/v1/tickets/my-tickets"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(ticketService, userRepository);
    }
}

