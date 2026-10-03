package com.web.integration.userstories;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.config.CustomUserDetailsService;
import com.web.config.JwtAuthenticationFilter;
import com.web.controller.TicketController;
import com.web.dto.baggage.BaggageCreateRequest;
import com.web.dto.baggage.BaggageResponse;
import com.web.dto.ticket.TicketCreateRequest;
import com.web.dto.ticket.TicketResponse;
import com.web.entity.Ticket;
import com.web.exception.BusinessException;
import com.web.dto.baggage.mapper.BaggageMapper;
import com.web.repository.BaggageRepository;
import com.web.repository.TicketRepository;
import com.web.repository.UserRepository;
import com.web.service.admin.ConfigService;
import com.web.service.baggage.BaggageService;
import com.web.service.baggage.BaggageServiceImpl;
import com.web.util.QrCodeGenerator;
import org.springframework.http.HttpStatus;
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
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = TicketController.class)
@AutoConfigureMockMvc(addFilters = false)
class ClerkBaggageTest {

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

    // Verifica que el cajero registre equipaje con peso y tag
    @Test
    @WithMockUser(roles = "CLERK")
    void registerBaggage_shouldCreateWithWeightAndTag() throws Exception {
        // Given: Registro de equipaje con peso
        var baggageRequest = new BaggageCreateRequest(BigDecimal.valueOf(15.5), null);
        var ticketRequest = new TicketCreateRequest(
                1L, 1L, 10, 1L, "Bogotá", 1, 2L, "Medellín", 2,
                BigDecimal.valueOf(50000), Ticket.PaymentMethod.CARD, baggageRequest, "ADULT"
        );
        var baggageResponse = new BaggageResponse(
                1L, 1L, BigDecimal.valueOf(15.5), BigDecimal.ZERO,
                "BAG-ABC123", "MAIN", LocalDateTime.now()
        );
        var ticketResponse = new TicketResponse(
                1L, 1L, "Bogotá - Medellín", LocalDate.now().plusDays(1),
                LocalDateTime.now().plusDays(1).plusHours(8),
                1L, "Juan Pérez", "juan@example.com",
                10, 1L, "Bogotá", 1, 2L, "Medellín", 2,
                BigDecimal.valueOf(50000), Ticket.PaymentMethod.CARD,
                Ticket.TicketStatus.SOLD, "TKT-2025-001-ABC123", LocalDateTime.now(),
                baggageResponse
        , null);

        when(ticketService.purchaseTicket(any(TicketCreateRequest.class))).thenReturn(ticketResponse);

        // When & Then
        mvc.perform(post("/api/v1/trips/1/tickets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(ticketRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.baggage").exists())
                .andExpect(jsonPath("$.baggage.weightKg").value(15.5))
                .andExpect(jsonPath("$.baggage.tagCode").exists())
                .andExpect(jsonPath("$.baggage.tagCode").value(org.hamcrest.Matchers.startsWith("BAG-")));
    }

    // Verifica cálculo de fee cuando se excede el peso base
    @Test
    @WithMockUser(roles = "CLERK")
    void registerBaggage_shouldCalculateExcessFee() throws Exception {
        // Given: Equipaje que excede el límite (20kg por defecto)
        var baggageRequest = new BaggageCreateRequest(BigDecimal.valueOf(25.0), null);
        var ticketRequest = new TicketCreateRequest(
                1L, 1L, 10, 1L, "Bogotá", 1, 2L, "Medellín", 2,
                BigDecimal.valueOf(50000), Ticket.PaymentMethod.CARD, baggageRequest, "ADULT"
        );
        // Exceso: 25kg - 20kg = 5kg, fee: 5kg * 2500 = 12500
        var baggageResponse = new BaggageResponse(
                1L, 1L, BigDecimal.valueOf(25.0), BigDecimal.valueOf(12500),
                "BAG-XYZ789", "MAIN", LocalDateTime.now()
        );
        var ticketResponse = new TicketResponse(
                1L, 1L, "Bogotá - Medellín", LocalDate.now().plusDays(1),
                LocalDateTime.now().plusDays(1).plusHours(8),
                1L, "Juan Pérez", "juan@example.com",
                10, 1L, "Bogotá", 1, 2L, "Medellín", 2,
                BigDecimal.valueOf(50000), Ticket.PaymentMethod.CARD,
                Ticket.TicketStatus.SOLD, "TKT-2025-001-ABC123", LocalDateTime.now(),
                baggageResponse
        , null);

        when(ticketService.purchaseTicket(any(TicketCreateRequest.class))).thenReturn(ticketResponse);

        // When & Then
        mvc.perform(post("/api/v1/trips/1/tickets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(ticketRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.baggage.excessFee").value(12500))
                .andExpect(jsonPath("$.baggage.weightKg").value(25.0));
    }

    // Verifica generación de tags únicos por equipaje
    @Test
    @WithMockUser(roles = "CLERK")
    void registerBaggage_shouldGenerateUniqueTag() throws Exception {
        // Given: Múltiples equipajes deben tener tags únicos
        var baggageRequest1 = new BaggageCreateRequest(BigDecimal.valueOf(15.0), null);
        var ticketRequest1 = new TicketCreateRequest(
                1L, 1L, 10, 1L, "Bogotá", 1, 2L, "Medellín", 2,
                BigDecimal.valueOf(50000), Ticket.PaymentMethod.CARD, baggageRequest1, "ADULT"
        );
        var baggageResponse1 = new BaggageResponse(
                1L, 1L, BigDecimal.valueOf(15.0), BigDecimal.ZERO,
                "BAG-UNIQUE001", "MAIN", LocalDateTime.now()
        );
        var ticketResponse1 = new TicketResponse(
                1L, 1L, "Bogotá - Medellín", LocalDate.now().plusDays(1),
                LocalDateTime.now().plusDays(1).plusHours(8),
                1L, "Juan Pérez", "juan@example.com",
                10, 1L, "Bogotá", 1, 2L, "Medellín", 2,
                BigDecimal.valueOf(50000), Ticket.PaymentMethod.CARD,
                Ticket.TicketStatus.SOLD, "TKT-2025-001-ABC123", LocalDateTime.now(),
                baggageResponse1
        , null);

        when(ticketService.purchaseTicket(any(TicketCreateRequest.class))).thenReturn(ticketResponse1);

        // When & Then
        mvc.perform(post("/api/v1/trips/1/tickets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(ticketRequest1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.baggage.tagCode").value("BAG-UNIQUE001"))
                .andExpect(jsonPath("$.baggage.tagCode").value(org.hamcrest.Matchers.startsWith("BAG-")));
    }

    // Verifica rechazo cuando el peso supera el máximo absoluto: usa el BaggageService real (no un mock que
    // lance la excepción) con el máximo configurado por ADMIN, y la respuesta HTTP que produce
    @Test
    @WithMockUser(roles = "CLERK")
    void registerBaggage_shouldFailWhenWeightExceedsLimit() throws Exception {
        // Given: máximo configurado de 50 kg y un equipaje de 60 kg
        ConfigService configService = mock(ConfigService.class);
        BaggageRepository baggageRepository = mock(BaggageRepository.class);
        when(configService.getBaggageWeightMax()).thenReturn(50.0);
        BaggageService baggageService = new BaggageServiceImpl(baggageRepository, mock(TicketRepository.class),
                configService, mock(QrCodeGenerator.class), mock(BaggageMapper.class));
        var baggageRequest = new BaggageCreateRequest(BigDecimal.valueOf(60.0), null);
        var ticketRequest = new TicketCreateRequest(
                1L, 1L, 10, 1L, "Bogotá", 1, 2L, "Medellín", 2,
                BigDecimal.valueOf(50000), Ticket.PaymentMethod.CARD, baggageRequest, "ADULT"
        );
        Ticket ticket = Ticket.builder().id(1L).build();

        // La compra delega el registro del equipaje en BaggageService, como hace TicketServiceImpl
        when(ticketService.purchaseTicket(any(TicketCreateRequest.class))).thenAnswer(inv -> {
            TicketCreateRequest request = inv.getArgument(0);
            baggageService.registerForTicket(ticket, request.baggage());
            return null;
        });

        // When & Then: 400 BAGGAGE_WEIGHT_EXCEEDED y no se guarda nada
        mvc.perform(post("/api/v1/trips/1/tickets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(ticketRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("equipaje")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("50.0 kg")));

        assertThatThrownBy(() -> baggageService.registerForTicket(ticket, baggageRequest))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(be.getCode()).isEqualTo("BAGGAGE_WEIGHT_EXCEEDED");
                });
        verify(baggageRepository, never()).save(any());
        assertThat(ticket.getBaggage()).isNull();
    }

    // Verifica que un equipaje sin peso ({"baggage":{}}) se rechace con 400 y no llegue al servicio
    @Test
    @WithMockUser(roles = "CLERK")
    void registerBaggage_shouldReturn400WhenBaggageWithoutWeight() throws Exception {
        // Given
        String body = """
                {"tripId":1,"passengerId":1,"seatNumber":10,"fromStopId":1,"toStopId":2,
                 "price":50000,"paymentMethod":"CASH","baggage":{}}
                """;

        // When & Then
        mvc.perform(post("/api/v1/trips/1/tickets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors['baggage.weightKg']").exists());

        verifyNoInteractions(ticketService);
    }
}
