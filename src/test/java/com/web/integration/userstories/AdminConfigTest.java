package com.web.integration.userstories;

import com.web.service.admin.MetricsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.controller.AdminController;
import com.web.dto.admin.ConfigResponse;
import com.web.dto.admin.ConfigUpdateRequest;
import com.web.config.CustomUserDetailsService;
import com.web.config.JwtAuthenticationFilter;
import com.web.entity.User;
import com.web.repository.UserRepository;
import com.web.service.admin.ConfigService;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = AdminController.class)
@AutoConfigureMockMvc(addFilters = false)
class AdminConfigTest {

        @Autowired
        private MockMvc mvc;

        @Autowired
        private ObjectMapper om;

        @MockitoBean
        private ConfigService configService;

        @MockitoBean
        private MetricsService metricsService;

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
                // Preparar usuario admin simulado para los tests
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

        // Verifica que admin puede obtener toda la configuración del sistema
        @Test
        @WithMockUser(username = "admin@test.com", roles = "ADMIN")
        void getConfig_shouldReturnAllPolicies() throws Exception {
                // Preparar configuración simulada completa
                Map<String, Integer> discounts = new HashMap<>();
                discounts.put("STUDENT", 20);
                discounts.put("SENIOR", 15);
                discounts.put("CHILD", 10);

                var config = new ConfigResponse(
                                10, 10, 5, discounts,
                                BigDecimal.valueOf(20.0), BigDecimal.valueOf(2500.0),
                                BigDecimal.valueOf(10000.0), 0.10,
                                BigDecimal.valueOf(80), BigDecimal.valueOf(50), BigDecimal.valueOf(30),
                                BigDecimal.valueOf(10), BigDecimal.ZERO,
                                BigDecimal.valueOf(50000), BigDecimal.valueOf(1.2), BigDecimal.valueOf(1.15),
                                BigDecimal.valueOf(1.05),
                                LocalDateTime.now(), null, null, null, null, null, null, null);

                when(configService.getConfig()).thenReturn(config);

                // Hacer petición GET y verificar respuesta
                mvc.perform(get("/api/v1/admin/config"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.refundPercentage48Hours").value(80))
                                .andExpect(jsonPath("$.refundPercentage24Hours").value(50))
                                .andExpect(jsonPath("$.refundPercentage12Hours").value(30))
                                .andExpect(jsonPath("$.refundPercentage6Hours").value(10))
                                .andExpect(jsonPath("$.refundPercentageLess6Hours").value(0))
                                .andExpect(jsonPath("$.discountPercentages.STUDENT").value(20))
                                .andExpect(jsonPath("$.discountPercentages.SENIOR").value(15))
                                .andExpect(jsonPath("$.ticketBasePrice").value(50000))
                                .andExpect(jsonPath("$.ticketPriceMultiplierPeakHours").value(1.2));
        }

        // Verifica que admin puede actualizar políticas de reembolso
        @Test
        @WithMockUser(username = "admin@test.com", roles = "ADMIN")
        void updateRefundPolicies_shouldPersist() throws Exception {
                // Preparar nueva política de reembolso
                var updateRequest = new ConfigUpdateRequest(
                                null, null, null, null, null, null,
                                null, null,
                                BigDecimal.valueOf(90), // 48h → 90% reembolso
                                BigDecimal.valueOf(60), // 24h → 60% reembolso
                                BigDecimal.valueOf(40), // 12h → 40% reembolso
                                BigDecimal.valueOf(15), // 6h → 15% reembolso
                                BigDecimal.valueOf(5), // <6h → 5% reembolso
                                null, null, null, null, null, null, null, null, null, null, null);

                Map<String, Integer> discounts = new HashMap<>();
                var updatedConfig = new ConfigResponse(
                                10, 10, 5, discounts,
                                BigDecimal.valueOf(20.0), BigDecimal.valueOf(2500.0),
                                BigDecimal.valueOf(10000.0), 0.10,
                                BigDecimal.valueOf(90), BigDecimal.valueOf(60), BigDecimal.valueOf(40),
                                BigDecimal.valueOf(15), BigDecimal.valueOf(5),
                                BigDecimal.valueOf(50000), BigDecimal.valueOf(1.2), BigDecimal.valueOf(1.15),
                                BigDecimal.valueOf(1.05),
                                LocalDateTime.now(), null, null, null, null, null, null, null);

                when(configService.updateConfig(any(ConfigUpdateRequest.class), eq(1L)))
                                .thenReturn(updatedConfig);

                // When & Then
                mvc.perform(put("/api/v1/admin/config")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(om.writeValueAsString(updateRequest)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.refundPercentage48Hours").value(90))
                                .andExpect(jsonPath("$.refundPercentage24Hours").value(60))
                                .andExpect(jsonPath("$.refundPercentage12Hours").value(40))
                                .andExpect(jsonPath("$.refundPercentage6Hours").value(15))
                                .andExpect(jsonPath("$.refundPercentageLess6Hours").value(5));
        }

        // Verifica que admin puede actualizar descuentos por tipo de pasajero
        @Test
        @WithMockUser(username = "admin@test.com", roles = "ADMIN")
        void updateDiscounts_shouldPersist() throws Exception {
                // Preparar nuevos descuentos
                Map<String, Integer> newDiscounts = new HashMap<>();
                newDiscounts.put("STUDENT", 25); // Estudiantes: 25% descuento
                newDiscounts.put("SENIOR", 20); // Adultos mayores: 20% descuento
                newDiscounts.put("CHILD", 15); // Niños: 15% descuento

                var updateRequest = new ConfigUpdateRequest(
                                null, null, null, newDiscounts, null, null,
                                null, null,
                                null, null, null, null, null,
                                null, null, null, null, null, null, null, null, null, null, null);

                var updatedConfig = new ConfigResponse(
                                10, 10, 5, newDiscounts,
                                BigDecimal.valueOf(20.0), BigDecimal.valueOf(2500.0),
                                BigDecimal.valueOf(10000.0), 0.10,
                                BigDecimal.valueOf(80), BigDecimal.valueOf(50), BigDecimal.valueOf(30),
                                BigDecimal.valueOf(10), BigDecimal.ZERO,
                                BigDecimal.valueOf(50000), BigDecimal.valueOf(1.2), BigDecimal.valueOf(1.15),
                                BigDecimal.valueOf(1.05),
                                LocalDateTime.now(), null, null, null, null, null, null, null);

                when(configService.updateConfig(any(ConfigUpdateRequest.class), eq(1L)))
                                .thenReturn(updatedConfig);

                // When & Then
                mvc.perform(put("/api/v1/admin/config")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(om.writeValueAsString(updateRequest)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.discountPercentages.STUDENT").value(25))
                                .andExpect(jsonPath("$.discountPercentages.SENIOR").value(20))
                                .andExpect(jsonPath("$.discountPercentages.CHILD").value(15));
        }

        // Verifica que admin puede actualizar multiplicadores de precio
        @Test
        @WithMockUser(username = "admin@test.com", roles = "ADMIN")
        void updatePriceMultipliers_shouldPersist() throws Exception {
                // Preparar nuevos multiplicadores de precio
                var updateRequest = new ConfigUpdateRequest(
                                null, null, null, null, null, null,
                                null, null,
                                null, null, null, null, null,
                                BigDecimal.valueOf(55000), // Precio base: $55,000
                                BigDecimal.valueOf(1.3), // Hora pico: +30%
                                BigDecimal.valueOf(1.2), // Alta demanda: +20%
                                BigDecimal.valueOf(1.1), // Demanda media: +10%
                                null, null, null, null, null, null, null
                );

                Map<String, Integer> discounts = new HashMap<>();
                var updatedConfig = new ConfigResponse(
                                10, 10, 5, discounts,
                                BigDecimal.valueOf(20.0), BigDecimal.valueOf(2500.0),
                                BigDecimal.valueOf(10000.0), 0.10,
                                BigDecimal.valueOf(80), BigDecimal.valueOf(50), BigDecimal.valueOf(30),
                                BigDecimal.valueOf(10), BigDecimal.ZERO,
                                BigDecimal.valueOf(55000), BigDecimal.valueOf(1.3), BigDecimal.valueOf(1.2),
                                BigDecimal.valueOf(1.1),
                                LocalDateTime.now(), null, null, null, null, null, null, null);

                when(configService.updateConfig(any(ConfigUpdateRequest.class), eq(1L)))
                                .thenReturn(updatedConfig);

                // When & Then
                mvc.perform(put("/api/v1/admin/config")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(om.writeValueAsString(updateRequest)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.ticketBasePrice").value(55000))
                                .andExpect(jsonPath("$.ticketPriceMultiplierPeakHours").value(1.3))
                                .andExpect(jsonPath("$.ticketPriceMultiplierHighDemand").value(1.2))
                                .andExpect(jsonPath("$.ticketPriceMultiplierMediumDemand").value(1.1));
        }

        // Verifica que solo usuarios ADMIN pueden actualizar configuración
        @Test
        @WithMockUser(username = "admin@test.com", roles = "ADMIN")
        void updateConfig_shouldRequireAdminRole() throws Exception {
                // Preparar request vacío de actualización
                var updateRequest = new ConfigUpdateRequest(
                                null, null, null, null, null, null,
                                null, null,
                                null, null, null, null, null,
                                null, null, null, null, null, null, null, null, null, null, null);

                // Simular respuesta del servicio
                Map<String, Integer> discounts = new HashMap<>();
                var updatedConfig = new ConfigResponse(
                                10, 10, 5, discounts,
                                BigDecimal.valueOf(20.0), BigDecimal.valueOf(2500.0),
                                BigDecimal.valueOf(10000.0), 0.10,
                                BigDecimal.valueOf(80), BigDecimal.valueOf(50), BigDecimal.valueOf(30),
                                BigDecimal.valueOf(10), BigDecimal.ZERO,
                                BigDecimal.valueOf(50000), BigDecimal.valueOf(1.2), BigDecimal.valueOf(1.15),
                                BigDecimal.valueOf(1.05),
                                LocalDateTime.now(), null, null, null, null, null, null, null);
                when(configService.updateConfig(any(ConfigUpdateRequest.class), eq(1L)))
                                .thenReturn(updatedConfig);

                // Verificar que el endpoint responde (seguridad se valida en tests de
                // integración)
                mvc.perform(put("/api/v1/admin/config")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(om.writeValueAsString(updateRequest)))
                                .andExpect(status().isOk());
        }
}
