package com.web.controller;

import com.web.service.admin.MetricsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.dto.admin.ConfigResponse;
import com.web.dto.admin.ConfigUpdateRequest;
import com.web.config.CustomUserDetailsService;
import com.web.config.SecurityConfig;
import com.web.entity.User;
import com.web.exception.BusinessException;
import com.web.repository.UserRepository;
import com.web.service.admin.ConfigService;
import com.web.util.JwtTokenProvider;
import org.junit.jupiter.api.Test;
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
                LocalDateTime.now()
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
                BigDecimal.valueOf(1.3), BigDecimal.valueOf(1.15)
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
                LocalDateTime.now()
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

    // Verifica manejo de error cuando el usuario administrador no existe
    @Test
    @WithMockUser(username = "admin@example.com", roles = "ADMIN")
    void updateConfig_shouldReturn404WhenUserNotFound() throws Exception {
        Map<String, Integer> discounts = new HashMap<>();
        var req = new ConfigUpdateRequest(
                15, null, null, discounts,
                null, null, null, null,
                null, null, null, null, null,
                null, null, null, null
        );

        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.empty());

        mvc.perform(put("/api/v1/admin/config")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isInternalServerError());
    }

    // ---------------------------------------------------------------------
    // Casos adicionales: validación, seguridad y errores propagados
    // ---------------------------------------------------------------------

    private ConfigUpdateRequest minimalUpdateRequest() {
        return new ConfigUpdateRequest(
                20, null, null, null,
                null, null, null, null,
                null, null, null, null, null,
                null, null, null, null
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
                LocalDateTime.now()
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
}

