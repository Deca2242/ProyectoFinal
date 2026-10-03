package com.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.config.CustomUserDetailsService;
import com.web.config.SecurityConfig;
import com.web.dto.dispatch.OverbookingPolicy.OverbookingPolicyCreateRequest;
import com.web.dto.dispatch.OverbookingPolicy.OverbookingPolicyResponse;
import com.web.exception.BusinessException;
import com.web.exception.ResourceNotFoundException;
import com.web.service.dispatch.OverbookingPolicyService;
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
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(OverbookingPolicyController.class)
@Import(SecurityConfig.class)
class OverbookingPolicyControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper om;

    @MockitoBean
    private OverbookingPolicyService policyService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private CustomUserDetailsService customUserDetailsService;

    private OverbookingPolicyResponse response() {
        return new OverbookingPolicyResponse(1L, 1L, 6, 10, new BigDecimal("0.1000"), 7L, "Despachador",
                LocalDateTime.now());
    }

    private String body(Integer start, Integer end, String percentage) throws Exception {
        return om.writeValueAsString(new OverbookingPolicyCreateRequest(start, end,
                percentage == null ? null : new BigDecimal(percentage)));
    }

    // Verifica que un DISPATCHER pueda listar las políticas de una ruta
    @Test
    @WithMockUser(roles = "DISPATCHER")
    void getPolicies_shouldReturn200ForDispatcher() throws Exception {
        when(policyService.getPoliciesByRoute(1L)).thenReturn(List.of(response()));

        mvc.perform(get("/api/v1/routes/1/overbooking-policies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].startHour").value(6))
                .andExpect(jsonPath("$[0].maxPercentage").value(0.1));
    }

    // Verifica que un ADMIN también pueda consultarlas
    @Test
    @WithMockUser(roles = "ADMIN")
    void getPolicies_shouldReturn200ForAdmin() throws Exception {
        when(policyService.getPoliciesByRoute(1L)).thenReturn(List.of());

        mvc.perform(get("/api/v1/routes/1/overbooking-policies"))
                .andExpect(status().isOk());
    }

    // Verifica 404 cuando la ruta no existe
    @Test
    @WithMockUser(roles = "DISPATCHER")
    void getPolicies_shouldReturn404WhenRouteNotFound() throws Exception {
        when(policyService.getPoliciesByRoute(99L)).thenThrow(new ResourceNotFoundException("Ruta", 99L));

        mvc.perform(get("/api/v1/routes/99/overbooking-policies"))
                .andExpect(status().isNotFound());
    }

    // Verifica que la consulta no sea pública (a diferencia de las rutas)
    @Test
    void getPolicies_shouldReturn401WhenAnonymous() throws Exception {
        mvc.perform(get("/api/v1/routes/1/overbooking-policies"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(policyService);
    }

    // Verifica que un PASSENGER no pueda consultarlas
    @Test
    @WithMockUser(roles = "PASSENGER")
    void getPolicies_shouldReturn403ForPassenger() throws Exception {
        mvc.perform(get("/api/v1/routes/1/overbooking-policies"))
                .andExpect(status().isForbidden());
    }

    // Verifica que un DISPATCHER pueda crear una política
    @Test
    @WithMockUser(roles = "DISPATCHER")
    void createPolicy_shouldReturn201() throws Exception {
        when(policyService.createPolicy(eq(1L), any(OverbookingPolicyCreateRequest.class))).thenReturn(response());

        mvc.perform(post("/api/v1/routes/1/overbooking-policies").contentType(MediaType.APPLICATION_JSON)
                        .content(body(6, 10, "0.10")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1));
    }

    // Verifica la validación de rangos de horas y porcentaje
    @Test
    @WithMockUser(roles = "DISPATCHER")
    void createPolicy_shouldReturn400WhenOutOfRange() throws Exception {
        mvc.perform(post("/api/v1/routes/1/overbooking-policies").contentType(MediaType.APPLICATION_JSON)
                        .content(body(24, 25, "1.5")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.startHour").exists())
                .andExpect(jsonPath("$.validationErrors.endHour").exists())
                .andExpect(jsonPath("$.validationErrors.maxPercentage").exists());
        verifyNoInteractions(policyService);
    }

    // Verifica la validación de campos obligatorios
    @Test
    @WithMockUser(roles = "ADMIN")
    void createPolicy_shouldReturn400WhenMissingFields() throws Exception {
        mvc.perform(post("/api/v1/routes/1/overbooking-policies").contentType(MediaType.APPLICATION_JSON)
                        .content(body(null, null, null)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(policyService);
    }

    // Verifica 409 cuando la franja se solapa con otra
    @Test
    @WithMockUser(roles = "DISPATCHER")
    void createPolicy_shouldReturn409WhenOverlapping() throws Exception {
        when(policyService.createPolicy(eq(1L), any())).thenThrow(new BusinessException(
                "La franja se solapa", HttpStatus.CONFLICT, "OVERBOOKING_POLICY_OVERLAP"));

        mvc.perform(post("/api/v1/routes/1/overbooking-policies").contentType(MediaType.APPLICATION_JSON)
                        .content(body(6, 10, "0.10")))
                .andExpect(status().isConflict());
    }

    // Verifica que un CLERK no pueda crear políticas
    @Test
    @WithMockUser(roles = "CLERK")
    void createPolicy_shouldReturn403ForClerk() throws Exception {
        mvc.perform(post("/api/v1/routes/1/overbooking-policies").contentType(MediaType.APPLICATION_JSON)
                        .content(body(6, 10, "0.10")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(policyService);
    }

    // Verifica que un ADMIN pueda eliminar una política
    @Test
    @WithMockUser(roles = "ADMIN")
    void deletePolicy_shouldReturn204() throws Exception {
        mvc.perform(delete("/api/v1/overbooking-policies/1"))
                .andExpect(status().isNoContent());
        verify(policyService).deletePolicy(1L);
    }

    // Verifica 404 al eliminar una política inexistente
    @Test
    @WithMockUser(roles = "DISPATCHER")
    void deletePolicy_shouldReturn404WhenNotFound() throws Exception {
        doThrow(new ResourceNotFoundException("Política de overbooking", 99L)).when(policyService).deletePolicy(99L);

        mvc.perform(delete("/api/v1/overbooking-policies/99"))
                .andExpect(status().isNotFound());
    }

    // Verifica 403 y 401 al eliminar
    @Test
    @WithMockUser(roles = "DRIVER")
    void deletePolicy_shouldReturn403ForDriver() throws Exception {
        mvc.perform(delete("/api/v1/overbooking-policies/1"))
                .andExpect(status().isForbidden());
    }

    @Test
    void deletePolicy_shouldReturn401WhenAnonymous() throws Exception {
        mvc.perform(delete("/api/v1/overbooking-policies/1"))
                .andExpect(status().isUnauthorized());
    }
}
