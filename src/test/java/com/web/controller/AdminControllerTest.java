package com.web.controller;

import com.web.service.admin.MetricsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.dto.admin.ConfigResponse;
import com.web.dto.admin.ConfigUpdateRequest;
import com.web.dto.admin.MetricsResponse;
import com.web.dto.admin.OccupancyMetrics;
import com.web.dto.admin.OperationalMetrics;
import com.web.dto.admin.ParcelMetrics;
import com.web.dto.admin.RevenueMetrics;
import com.web.config.CustomUserDetailsService;
import com.web.config.SecurityConfig;
import com.web.entity.User;
import com.web.exception.BusinessException;
import com.web.repository.UserRepository;
import com.web.service.admin.ConfigService;
import com.web.util.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;


@WebMvcTest(AdminController.class)
@Import(SecurityConfig.class)
class  AdminControllerTest {

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
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private CustomUserDetailsService customUserDetailsService;

    // Verifica que un ADMIN pueda consultar la configuración del sistema
    @Test
    @WithMockUser(roles = "ADMIN")
    void getConfig_shouldReturn200() throws Exception {
        Map<String, Integer> discounts = new HashMap<>();
        discounts.put("STUDENT", 20);
        discounts.put("SENIOR", 15);
        discounts.put("CHILD", 50);

        var resp = new ConfigResponse(
                10, 10, 5, discounts,
                BigDecimal.valueOf(23.0), BigDecimal.valueOf(5000),
                BigDecimal.valueOf(10000), 0.05,
                BigDecimal.valueOf(90), BigDecimal.valueOf(70),
                BigDecimal.valueOf(50), BigDecimal.valueOf(30), BigDecimal.ZERO,
                BigDecimal.valueOf(50000), BigDecimal.valueOf(1.15),
                BigDecimal.valueOf(1.2), BigDecimal.valueOf(1.1),
                LocalDateTime.now(), null, null, null, null, null, null, null
        );

        when(configService.getConfig()).thenReturn(resp);

        mvc.perform(get("/api/v1/admin/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.holdDurationMinutes").value(10))
                .andExpect(jsonPath("$.overbookingMaxPercentage").value(0.05));
    }

    // Verifica que un ADMIN pueda actualizar la configuración del sistema
    @Test
    @WithMockUser(username = "admin@example.com", roles = "ADMIN")
    void updateConfig_shouldReturn200() throws Exception {
        Map<String, Integer> discounts = new HashMap<>();
        discounts.put("STUDENT", 25);

        var req = new ConfigUpdateRequest(
                15, 10, 5, discounts,
                BigDecimal.valueOf(25.0), BigDecimal.valueOf(6000),
                BigDecimal.valueOf(15000), 0.1,
                BigDecimal.valueOf(95), BigDecimal.valueOf(75),
                BigDecimal.valueOf(55), BigDecimal.valueOf(35), BigDecimal.ZERO,
                BigDecimal.valueOf(55000), BigDecimal.valueOf(1.2),
                BigDecimal.valueOf(1.3), BigDecimal.valueOf(1.15), null, null, null, null, null, null, null
        );

        var user = User.builder()
                .id(1L)
                .email("admin@example.com")
                .build();

        var resp = new ConfigResponse(
                15, 10, 5, discounts,
                BigDecimal.valueOf(25.0), BigDecimal.valueOf(6000),
                BigDecimal.valueOf(15000), 0.1,
                BigDecimal.valueOf(95), BigDecimal.valueOf(75),
                BigDecimal.valueOf(55), BigDecimal.valueOf(35), BigDecimal.ZERO,
                BigDecimal.valueOf(55000), BigDecimal.valueOf(1.2),
                BigDecimal.valueOf(1.3), BigDecimal.valueOf(1.15),
                LocalDateTime.now(), null, null, null, null, null, null, null
        );

        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(user));
        when(configService.updateConfig(any(), any())).thenReturn(resp);

        mvc.perform(put("/api/v1/admin/config")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.holdDurationMinutes").value(15));
    }

    // Verifica que si el administrador autenticado ya no existe se responda 401 (antes era un 500)
    @Test
    @WithMockUser(username = "admin@example.com", roles = "ADMIN")
    void updateConfig_shouldReturn401WhenUserNotFound() throws Exception {
        Map<String, Integer> discounts = new HashMap<>();
        var req = new ConfigUpdateRequest(
                15, null, null, discounts,
                null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null
        );

        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.empty());

        mvc.perform(put("/api/v1/admin/config")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Usuario autenticado no encontrado"));

        verifyNoInteractions(configService);
    }

    // ---------------------------------------------------------------------
    // Casos adicionales: validación, seguridad y errores propagados
    // ---------------------------------------------------------------------

    private ConfigUpdateRequest minimalUpdateRequest() {
        return new ConfigUpdateRequest(
                20, null, null, null,
                null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null
        );
    }

    private ConfigResponse minimalConfigResponse() {
        return new ConfigResponse(
                20, 10, 5, Map.of(),
                BigDecimal.valueOf(23.0), BigDecimal.valueOf(5000),
                BigDecimal.valueOf(10000), 0.05,
                BigDecimal.valueOf(90), BigDecimal.valueOf(70),
                BigDecimal.valueOf(50), BigDecimal.valueOf(30), BigDecimal.ZERO,
                BigDecimal.valueOf(50000), BigDecimal.valueOf(1.15),
                BigDecimal.valueOf(1.2), BigDecimal.valueOf(1.1),
                LocalDateTime.now(), null, null, null, null, null, null, null
        );
    }

    // Verifica que consultar la configuración sin autenticación devuelva 401
    @Test
    void getConfig_shouldReturn401WhenNotAuthenticated() throws Exception {
        mvc.perform(get("/api/v1/admin/config"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("No autenticado"));

        verifyNoInteractions(configService);
    }

    // Verifica que un DISPATCHER no pueda consultar la configuración (403)
    @Test
    @WithMockUser(roles = "DISPATCHER")
    void getConfig_shouldReturn403WhenDispatcher() throws Exception {
        mvc.perform(get("/api/v1/admin/config"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Acceso denegado"));

        verifyNoInteractions(configService);
    }

    // Verifica que un PASSENGER no pueda consultar la configuración (403)
    @Test
    @WithMockUser(roles = "PASSENGER")
    void getConfig_shouldReturn403WhenPassenger() throws Exception {
        mvc.perform(get("/api/v1/admin/config"))
                .andExpect(status().isForbidden());
    }

    // Verifica que un error inesperado del servicio devuelva 500 sin exponer el detalle interno
    @Test
    @WithMockUser(roles = "ADMIN")
    void getConfig_shouldReturn500WithoutInternalMessage() throws Exception {
        when(configService.getConfig()).thenThrow(new IllegalStateException("could not execute statement; SQL [select * from config]"));

        mvc.perform(get("/api/v1/admin/config"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.message").value("Error interno del servidor"))
                .andExpect(content().string(not(containsString("SQL"))));
    }

    // Verifica que la actualización use el id del usuario autenticado
    @Test
    @WithMockUser(username = "admin@example.com", roles = "ADMIN")
    void updateConfig_shouldPassAuthenticatedUserIdToService() throws Exception {
        var req = minimalUpdateRequest();
        var user = User.builder().id(7L).email("admin@example.com").build();

        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(user));
        when(configService.updateConfig(any(), any())).thenReturn(minimalConfigResponse());

        mvc.perform(put("/api/v1/admin/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.holdDurationMinutes").value(20));

        verify(configService).updateConfig(eq(req), eq(7L));
    }

    // Verifica que actualizar la configuración sin autenticación devuelva 401
    @Test
    void updateConfig_shouldReturn401WhenNotAuthenticated() throws Exception {
        mvc.perform(put("/api/v1/admin/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(minimalUpdateRequest())))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(configService);
    }

    // Verifica que un CLERK no pueda actualizar la configuración (403)
    @Test
    @WithMockUser(roles = "CLERK")
    void updateConfig_shouldReturn403WhenNotAdmin() throws Exception {
        mvc.perform(put("/api/v1/admin/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(minimalUpdateRequest())))
                .andExpect(status().isForbidden());

        verifyNoInteractions(configService);
    }

    // Verifica que un JSON mal formado devuelva 400
    @Test
    @WithMockUser(username = "admin@example.com", roles = "ADMIN")
    void updateConfig_shouldReturn400WhenMalformedJson() throws Exception {
        mvc.perform(put("/api/v1/admin/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"holdDurationMinutes\": 15,"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        verifyNoInteractions(configService);
    }

    // Verifica que un valor con tipo incorrecto en el JSON devuelva 400
    @Test
    @WithMockUser(username = "admin@example.com", roles = "ADMIN")
    void updateConfig_shouldReturn400WhenFieldTypeInvalid() throws Exception {
        mvc.perform(put("/api/v1/admin/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"holdDurationMinutes\": \"diez\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(configService);
    }

    // Verifica que un rechazo de negocio del servicio se propague con su status (400)
    @Test
    @WithMockUser(username = "admin@example.com", roles = "ADMIN")
    void updateConfig_shouldReturn400WhenServiceRejectsValues() throws Exception {
        var user = User.builder().id(1L).email("admin@example.com").build();

        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(user));
        when(configService.updateConfig(any(), any())).thenThrow(
                new BusinessException("Valor de configuración inválido", HttpStatus.BAD_REQUEST, "INVALID_CONFIG"));

        mvc.perform(put("/api/v1/admin/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(minimalUpdateRequest())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Valor de configuración inválido"));
    }

    // Verifica que una violación de integridad al guardar la configuración devuelva 409
    @Test
    @WithMockUser(username = "admin@example.com", roles = "ADMIN")
    void updateConfig_shouldReturn409WhenDataIntegrityViolation() throws Exception {
        var user = User.builder().id(1L).email("admin@example.com").build();

        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(user));
        when(configService.updateConfig(any(), any())).thenThrow(new DataIntegrityViolationException("check constraint"));

        mvc.perform(put("/api/v1/admin/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(minimalUpdateRequest())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("La operación viola una restricción de integridad de los datos"));
    }

    // Verifica que un descuento fuera de 0..100 se rechace con 400 antes de llegar al servicio
    @Test
    @WithMockUser(username = "admin@example.com", roles = "ADMIN")
    void updateConfig_shouldReturn400WhenDiscountAboveHundred() throws Exception {
        mvc.perform(put("/api/v1/admin/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"discountPercentages\": {\"STUDENT\": 150}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Error de validación en los datos enviados"));

        verifyNoInteractions(configService);
    }

    // Verifica que un porcentaje máximo de overbooking mayor a 1 se rechace con 400
    @Test
    @WithMockUser(username = "admin@example.com", roles = "ADMIN")
    void updateConfig_shouldReturn400WhenOverbookingMaxPercentageAboveOne() throws Exception {
        mvc.perform(put("/api/v1/admin/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"overbookingMaxPercentage\": 1.5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.overbookingMaxPercentage").exists());

        verifyNoInteractions(configService);
    }

    // GET /metrics

    private MetricsResponse sampleMetrics() {
        return new MetricsResponse(
                new OccupancyMetrics(45.5, 40.0, 90.0, 12, 300),
                new RevenueMetrics(new BigDecimal("1500000"), new BigDecimal("1200000"), new BigDecimal("200000"),
                        new BigDecimal("50000"), new BigDecimal("50000"),
                        Map.of("CASH", new BigDecimal("700000")), Map.of("BOX_OFFICE", new BigDecimal("700000"))),
                new OperationalMetrics(91.7, null, 4.2, 3, 1, 5, null, null, null, null, java.util.List.of()),
                new ParcelMetrics(10, 8, 1, 88.9, Map.of("R1", 10), Map.of("A → B", 8), Map.of("A → B", 1)));
    }

    // Verifica que un ADMIN consulte los KPIs del rango de fechas
    @Test
    @WithMockUser(roles = "ADMIN")
    void getMetrics_shouldReturn200ForAdmin() throws Exception {
        when(metricsService.getMetrics(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31))).thenReturn(sampleMetrics());

        mvc.perform(get("/api/v1/admin/metrics")
                        .param("startDate", "2026-01-01")
                        .param("endDate", "2026-01-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.occupancy.averageOccupancy").value(45.5))
                .andExpect(jsonPath("$.occupancy.p95Occupancy").value(90.0))
                .andExpect(jsonPath("$.revenue.revenueByPaymentMethod.CASH").value(700000))
                .andExpect(jsonPath("$.revenue.revenueByChannel.BOX_OFFICE").value(700000))
                .andExpect(jsonPath("$.operational.onTimeDepartureRate").value(91.7))
                .andExpect(jsonPath("$.operational.totalNoShows").value(5))
                .andExpect(jsonPath("$.parcels.deliveredBySegment['A → B']").value(8));

        verify(metricsService).getMetrics(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));
    }

    // Verifica que los roles distintos de ADMIN no puedan consultar las métricas
    @ParameterizedTest
    @ValueSource(strings = {"DISPATCHER", "PASSENGER", "CLERK", "DRIVER"})
    void getMetrics_shouldReturn403ForOtherRoles(String role) throws Exception {
        mvc.perform(get("/api/v1/admin/metrics")
                        .with(user("u@test.com").roles(role))
                        .param("startDate", "2026-01-01")
                        .param("endDate", "2026-01-31"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(metricsService);
    }

    // Verifica que sin autenticación se responda 401
    @Test
    void getMetrics_shouldReturn401WhenNotAuthenticated() throws Exception {
        mvc.perform(get("/api/v1/admin/metrics")
                        .param("startDate", "2026-01-01")
                        .param("endDate", "2026-01-31"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(metricsService);
    }

    // Verifica que falte un parámetro obligatorio responda 400
    @Test
    @WithMockUser(roles = "ADMIN")
    void getMetrics_shouldReturn400WhenParameterMissing() throws Exception {
        mvc.perform(get("/api/v1/admin/metrics").param("startDate", "2026-01-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Parámetro inválido o faltante en la petición"));

        verifyNoInteractions(metricsService);
    }

    // Verifica que una fecha con formato inválido responda 400
    @Test
    @WithMockUser(roles = "ADMIN")
    void getMetrics_shouldReturn400WhenDateFormatInvalid() throws Exception {
        mvc.perform(get("/api/v1/admin/metrics")
                        .param("startDate", "01/01/2026")
                        .param("endDate", "2026-01-31"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(metricsService);
    }

    // Verifica que un rango invertido rechazado por el servicio responda 400
    @Test
    @WithMockUser(roles = "ADMIN")
    void getMetrics_shouldReturn400WhenRangeInvalid() throws Exception {
        when(metricsService.getMetrics(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 1, 1))).thenThrow(
                new BusinessException("La fecha final no puede ser anterior a la inicial",
                        HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE"));

        mvc.perform(get("/api/v1/admin/metrics")
                        .param("startDate", "2026-02-01")
                        .param("endDate", "2026-01-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("La fecha final no puede ser anterior a la inicial"));
    }

    // Verifica que la respuesta de métricas incluya los retrasos y el desglose de puntualidad por ruta
    @Test
    @WithMockUser(roles = "ADMIN")
    void getMetrics_shouldIncludeDelaysAndPunctualityByRoute() throws Exception {
        MetricsResponse sample = sampleMetrics();
        MetricsResponse withPunctuality = new MetricsResponse(sample.occupancy(), sample.revenue(),
                new OperationalMetrics(50.0, 100.0, 0.0, 0, 0, 0, 7.5, 15.0, 2.0, 4.0,
                        java.util.List.of(new com.web.dto.admin.RoutePunctuality(3L, "BOG-TUN", 2, 50.0, 100.0, 7.5))),
                sample.parcels());
        when(metricsService.getMetrics(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31))).thenReturn(withPunctuality);

        mvc.perform(get("/api/v1/admin/metrics")
                        .param("startDate", "2026-01-01")
                        .param("endDate", "2026-01-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operational.avgDepartureDelayMin").value(7.5))
                .andExpect(jsonPath("$.operational.p95DepartureDelayMin").value(15.0))
                .andExpect(jsonPath("$.operational.avgArrivalDelayMin").value(2.0))
                .andExpect(jsonPath("$.operational.p95ArrivalDelayMin").value(4.0))
                .andExpect(jsonPath("$.operational.punctualityByRoute[0].routeCode").value("BOG-TUN"))
                .andExpect(jsonPath("$.operational.punctualityByRoute[0].trips").value(2))
                .andExpect(jsonPath("$.operational.punctualityByRoute[0].onTimeDeparturePct").value(50.0));
    }

    // GET /metrics/punctuality

    // Verifica que un ADMIN consulte los reportes diarios de puntualidad del rango
    @Test
    @WithMockUser(roles = "ADMIN")
    void getPunctualityReports_shouldReturn200ForAdmin() throws Exception {
        var report = new com.web.dto.admin.PunctualityReportResponse(LocalDate.of(2026, 1, 9), 3L, "BOG-TUN",
                4, 75.0, 50.0, 6.25, 12.0, LocalDateTime.of(2026, 1, 10, 0, 5));
        when(metricsService.getPunctualityReports(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)))
                .thenReturn(java.util.List.of(report));

        mvc.perform(get("/api/v1/admin/metrics/punctuality")
                        .param("from", "2026-01-01")
                        .param("to", "2026-01-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].reportDate").value("2026-01-09"))
                .andExpect(jsonPath("$[0].routeCode").value("BOG-TUN"))
                .andExpect(jsonPath("$[0].trips").value(4))
                .andExpect(jsonPath("$[0].onTimeDeparturePct").value(75.0))
                .andExpect(jsonPath("$[0].avgArrivalDelayMin").value(12.0));
    }

    // Verifica que los demás roles reciban 403 y sin token 401
    @ParameterizedTest
    @ValueSource(strings = {"DISPATCHER", "PASSENGER", "CLERK", "DRIVER"})
    void getPunctualityReports_shouldReturn403ForOtherRoles(String role) throws Exception {
        mvc.perform(get("/api/v1/admin/metrics/punctuality")
                        .with(user("u@test.com").roles(role))
                        .param("from", "2026-01-01")
                        .param("to", "2026-01-31"))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/v1/admin/metrics/punctuality")
                        .param("from", "2026-01-01")
                        .param("to", "2026-01-31"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(metricsService);
    }

    // Verifica que falte el rango responda 400
    @Test
    @WithMockUser(roles = "ADMIN")
    void getPunctualityReports_shouldReturn400WhenParameterMissing() throws Exception {
        mvc.perform(get("/api/v1/admin/metrics/punctuality").param("from", "2026-01-01"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(metricsService);
    }

    // PUT /config: validaciones nuevas

    // Verifica que un multiplicador menor que 1, un precio base 0 o un precio por kg 0 respondan 400
    @Test
    @WithMockUser(username = "admin@example.com", roles = "ADMIN")
    void updateConfig_withMultiplierBelowOneOrNonPositivePrices_shouldReturn400() throws Exception {
        mvc.perform(put("/api/v1/admin/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ticketPriceMultiplierPeakHours\": 0.9, \"ticketBasePrice\": 0, \"baggagePricePerKg\": 0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.ticketPriceMultiplierPeakHours").exists())
                .andExpect(jsonPath("$.validationErrors.ticketBasePrice").exists())
                .andExpect(jsonPath("$.validationErrors.baggagePricePerKg").exists());

        verifyNoInteractions(configService);
    }

    // Verifica que las claves operativas nuevas se validen (ocupación mínima 0-1, intentos de OTP >= 1)
    @Test
    @WithMockUser(username = "admin@example.com", roles = "ADMIN")
    void updateConfig_withOperationalLimitsOutOfRange_shouldReturn400() throws Exception {
        mvc.perform(put("/api/v1/admin/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"overbookingMinOccupancy\": 1.2, \"parcelOtpMaxAttempts\": 0, \"baggageWeightMax\": 0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.overbookingMinOccupancy").exists())
                .andExpect(jsonPath("$.validationErrors.parcelOtpMaxAttempts").exists())
                .andExpect(jsonPath("$.validationErrors.baggageWeightMax").exists());

        verifyNoInteractions(configService);
    }

    // Verifica que la política de reembolso no monótona rechazada por el servicio responda 400
    @Test
    @WithMockUser(username = "admin@example.com", roles = "ADMIN")
    void updateConfig_withNonMonotonicRefundPolicy_shouldReturn400() throws Exception {
        when(userRepository.findByEmail("admin@example.com"))
                .thenReturn(Optional.of(User.builder().id(1L).email("admin@example.com").build()));
        when(configService.updateConfig(any(), eq(1L))).thenThrow(new BusinessException(
                "La política de reembolso debe ser no creciente", HttpStatus.BAD_REQUEST, "REFUND_POLICY_NOT_MONOTONIC"));

        mvc.perform(put("/api/v1/admin/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refundPercentage24Hours\": 95}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("no creciente")));
    }
}

