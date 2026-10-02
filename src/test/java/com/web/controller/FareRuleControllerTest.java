package com.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.config.CustomUserDetailsService;
import com.web.config.SecurityConfig;
import com.web.dto.catalog.FareRule.FareRuleCreateRequest;
import com.web.dto.catalog.FareRule.FareRuleResponse;
import com.web.dto.catalog.FareRule.FareRuleUpdateRequest;
import com.web.exception.BusinessException;
import com.web.exception.ResourceNotFoundException;
import com.web.service.catalog.FareRuleService;
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
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(FareRuleController.class)
@Import(SecurityConfig.class)
class FareRuleControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper om;

    @MockitoBean
    private FareRuleService fareRuleService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private CustomUserDetailsService customUserDetailsService;

    private FareRuleResponse response() {
        return new FareRuleResponse(1L, 1L, 10L, "Santa Marta", 1, 12L, "Barranquilla", 3,
                new BigDecimal("40000.00"), Map.of("STUDENT", 10), true);
    }

    private String createBody() throws Exception {
        return om.writeValueAsString(new FareRuleCreateRequest(10L, 12L, new BigDecimal("40000"),
                Map.of("STUDENT", 10), true));
    }

    // ---------- GET (público) ----------

    // Verifica que las tarifas de una ruta sean públicas
    @Test
    void getFareRules_shouldReturn200ForAnonymous() throws Exception {
        when(fareRuleService.getFareRulesByRoute(1L)).thenReturn(List.of(response()));

        mvc.perform(get("/api/v1/routes/1/fares").with(anonymous()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].basePrice").value(40000.00))
                .andExpect(jsonPath("$[0].discounts.STUDENT").value(10))
                .andExpect(jsonPath("$[0].fromStopName").value("Santa Marta"));
    }

    // Verifica 404 cuando la ruta no existe
    @Test
    void getFareRules_shouldReturn404WhenRouteNotFound() throws Exception {
        when(fareRuleService.getFareRulesByRoute(99L)).thenThrow(new ResourceNotFoundException("Ruta", 99L));

        mvc.perform(get("/api/v1/routes/99/fares"))
                .andExpect(status().isNotFound());
    }

    // ---------- POST ----------

    // Verifica que un ADMIN pueda crear una tarifa
    @Test
    @WithMockUser(roles = "ADMIN")
    void createFareRule_shouldReturn201() throws Exception {
        when(fareRuleService.createFareRule(eq(1L), any(FareRuleCreateRequest.class))).thenReturn(response());

        mvc.perform(post("/api/v1/routes/1/fares").contentType(MediaType.APPLICATION_JSON).content(createBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.dynamicPricingEnabled").value(true));
    }

    // Verifica la validación de precio positivo y paradas obligatorias
    @Test
    @WithMockUser(roles = "ADMIN")
    void createFareRule_shouldReturn400WhenInvalidBody() throws Exception {
        String body = om.writeValueAsString(new FareRuleCreateRequest(null, 12L, new BigDecimal("-1"), null, null));

        mvc.perform(post("/api/v1/routes/1/fares").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.basePrice").exists())
                .andExpect(jsonPath("$.validationErrors.fromStopId").exists());
        verifyNoInteractions(fareRuleService);
    }

    // Verifica 400 cuando dynamicPricingEnabled no es booleano
    @Test
    @WithMockUser(roles = "ADMIN")
    void createFareRule_shouldReturn400WhenDynamicPricingIsNotBoolean() throws Exception {
        String body = "{\"fromStopId\":10,\"toStopId\":12,\"basePrice\":40000,\"dynamicPricingEnabled\":\"quizas\"}";

        mvc.perform(post("/api/v1/routes/1/fares").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(fareRuleService);
    }

    // Verifica 400 cuando el servicio rechaza un descuento
    @Test
    @WithMockUser(roles = "ADMIN")
    void createFareRule_shouldReturn400WhenInvalidDiscount() throws Exception {
        when(fareRuleService.createFareRule(eq(1L), any())).thenThrow(new BusinessException(
                "Tipo de descuento no válido: VIP", HttpStatus.BAD_REQUEST, "INVALID_DISCOUNT_TYPE"));

        mvc.perform(post("/api/v1/routes/1/fares").contentType(MediaType.APPLICATION_JSON).content(createBody()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Tipo de descuento no válido: VIP"));
    }

    // Verifica 409 cuando el tramo ya tiene tarifa
    @Test
    @WithMockUser(roles = "ADMIN")
    void createFareRule_shouldReturn409WhenDuplicated() throws Exception {
        when(fareRuleService.createFareRule(eq(1L), any())).thenThrow(new BusinessException(
                "Ya existe una tarifa para ese tramo de la ruta", HttpStatus.CONFLICT, "FARE_RULE_EXISTS"));

        mvc.perform(post("/api/v1/routes/1/fares").contentType(MediaType.APPLICATION_JSON).content(createBody()))
                .andExpect(status().isConflict());
    }

    // Verifica que un DISPATCHER no pueda crear tarifas
    @Test
    @WithMockUser(roles = "DISPATCHER")
    void createFareRule_shouldReturn403ForDispatcher() throws Exception {
        mvc.perform(post("/api/v1/routes/1/fares").contentType(MediaType.APPLICATION_JSON).content(createBody()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(fareRuleService);
    }

    // Verifica 401 sin autenticación
    @Test
    void createFareRule_shouldReturn401WhenAnonymous() throws Exception {
        mvc.perform(post("/api/v1/routes/1/fares").contentType(MediaType.APPLICATION_JSON).content(createBody()))
                .andExpect(status().isUnauthorized());
    }

    // ---------- PUT / DELETE ----------

    // Verifica que un ADMIN pueda actualizar una tarifa
    @Test
    @WithMockUser(roles = "ADMIN")
    void updateFareRule_shouldReturn200() throws Exception {
        var request = new FareRuleUpdateRequest(null, null, new BigDecimal("45000"), null, false);
        when(fareRuleService.updateFareRule(1L, request)).thenReturn(response());

        mvc.perform(put("/api/v1/fares/1").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(request)))
                .andExpect(status().isOk());
        verify(fareRuleService).updateFareRule(1L, request);
    }

    // Verifica 400 con precio no positivo
    @Test
    @WithMockUser(roles = "ADMIN")
    void updateFareRule_shouldReturn400WhenPriceNotPositive() throws Exception {
        var request = new FareRuleUpdateRequest(null, null, BigDecimal.ZERO, null, null);

        mvc.perform(put("/api/v1/fares/1").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(fareRuleService);
    }

    // Verifica 404 cuando la tarifa no existe
    @Test
    @WithMockUser(roles = "ADMIN")
    void updateFareRule_shouldReturn404WhenNotFound() throws Exception {
        var request = new FareRuleUpdateRequest(null, null, BigDecimal.TEN, null, null);
        when(fareRuleService.updateFareRule(99L, request)).thenThrow(new ResourceNotFoundException("Tarifa", 99L));

        mvc.perform(put("/api/v1/fares/99").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(request)))
                .andExpect(status().isNotFound());
    }

    // Verifica que un PASSENGER no pueda actualizar tarifas
    @Test
    @WithMockUser(roles = "PASSENGER")
    void updateFareRule_shouldReturn403ForPassenger() throws Exception {
        mvc.perform(put("/api/v1/fares/1").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new FareRuleUpdateRequest(null, null, BigDecimal.TEN, null, null))))
                .andExpect(status().isForbidden());
    }

    // Verifica que un ADMIN pueda eliminar una tarifa
    @Test
    @WithMockUser(roles = "ADMIN")
    void deleteFareRule_shouldReturn204() throws Exception {
        mvc.perform(delete("/api/v1/fares/1"))
                .andExpect(status().isNoContent());
        verify(fareRuleService).deleteFareRule(1L);
    }

    // Verifica 404 al eliminar una tarifa inexistente
    @Test
    @WithMockUser(roles = "ADMIN")
    void deleteFareRule_shouldReturn404WhenNotFound() throws Exception {
        doThrow(new ResourceNotFoundException("Tarifa", 99L)).when(fareRuleService).deleteFareRule(99L);

        mvc.perform(delete("/api/v1/fares/99"))
                .andExpect(status().isNotFound());
    }

    // Verifica 403 y 401 al eliminar sin ser ADMIN
    @Test
    @WithMockUser(roles = "CLERK")
    void deleteFareRule_shouldReturn403ForClerk() throws Exception {
        mvc.perform(delete("/api/v1/fares/1"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(fareRuleService);
    }

    @Test
    void deleteFareRule_shouldReturn401WhenAnonymous() throws Exception {
        mvc.perform(delete("/api/v1/fares/1"))
                .andExpect(status().isUnauthorized());
    }
}
