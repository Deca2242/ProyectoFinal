package com.web.integration.userstories;

import com.web.service.admin.MetricsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.config.CustomUserDetailsService;
import com.web.config.JwtAuthenticationFilter;
import com.web.controller.AdminController;
import com.web.controller.TicketController;
import com.web.dto.admin.ConfigResponse;

import com.web.dto.ticket.TicketCreateRequest;
import com.web.dto.ticket.TicketResponse;
import com.web.entity.Ticket;
import com.web.entity.User;
import com.web.exception.OverbookingNotAllowedException;
import com.web.repository.UserRepository;
import com.web.service.admin.ConfigService;
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
import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {AdminController.class, TicketController.class})
@AutoConfigureMockMvc(addFilters = false)
class DispatcherOverbookingTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper om;

    @MockitoBean
    private ConfigService configService;

    @MockitoBean
    private MetricsService metricsService;

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

        User adminUser = User.builder()
                .id(1L)
                .email("admin@test.com")
                .name("Test Admin")
                .role(User.Role.ADMIN)
                .passwordHash("$2a$10$hashedpassword")
                .status(User.Status.ACTIVE)
                .build();
        when(userRepository.findByEmail("admin@test.com")).thenReturn(Optional.of(adminUser));
    }

    // Verifica que el admin consulte el porcentaje máximo de overbooking
    @Test
    @WithMockUser(username = "admin@test.com", roles = "ADMIN")
    void getConfig_shouldReturnOverbookingPercentage() throws Exception {
        // Given: Configuración con overbooking
        Map<String, Integer> discounts = new HashMap<>();
        discounts.put("STUDENT", 20);
        discounts.put("SENIOR", 15);
        
        var config = new ConfigResponse(
                10, 10, 5, discounts,
                BigDecimal.valueOf(20.0), BigDecimal.valueOf(2500.0),
                BigDecimal.valueOf(10000.0), 0.10, // 10% overbooking
                BigDecimal.valueOf(80), BigDecimal.valueOf(50), BigDecimal.valueOf(30),
                BigDecimal.valueOf(10), BigDecimal.ZERO,
                BigDecimal.valueOf(50000), BigDecimal.valueOf(1.2), BigDecimal.valueOf(1.15),
                BigDecimal.valueOf(1.05),
                java.time.LocalDateTime.now()
        );

        when(configService.getConfig()).thenReturn(config);

        // When & Then
        mvc.perform(get("/api/v1/admin/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overbookingMaxPercentage").value(0.10))
                .andExpect(jsonPath("$.overbookingMaxPercentage").exists());
    }

    // Verifica compra dentro del límite permitido de overbooking
    @Test
    @WithMockUser(roles = "PASSENGER")
    void purchaseTicket_shouldRespectOverbookingLimit() throws Exception {
        // Given: Compra dentro del límite de overbooking
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
                .andExpect(jsonPath("$.status").value("SOLD"));
    }

    // Verifica rechazo cuando se supera el límite configurado
    @Test
    @WithMockUser(roles = "PASSENGER")
    void purchaseTicket_shouldFailWhenLimitExceeded() throws Exception {
        // Given: Compra que excede el límite de overbooking
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
                // 403: "política de overbooking" según la tabla de errores estándar del proyecto
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("overbooking")));
    }
}

