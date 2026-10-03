package com.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.config.CustomUserDetailsService;
import com.web.config.SecurityConfig;
import com.web.dto.baggage.BaggageCreateRequest;
import com.web.dto.baggage.BaggageResponse;
import com.web.exception.BusinessException;
import com.web.exception.ResourceNotFoundException;
import com.web.service.baggage.BaggageService;
import com.web.util.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(BaggageController.class)
@Import(SecurityConfig.class)
class BaggageControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper om;

    @MockitoBean
    private BaggageService baggageService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private CustomUserDetailsService customUserDetailsService;

    private BaggageResponse response() {
        return new BaggageResponse(1L, 10L, new BigDecimal("25.00"), new BigDecimal("10000.00"), "BAG-1", "B",
                LocalDateTime.now());
    }

    private String body(String weight, String compartment) throws Exception {
        return om.writeValueAsString(new BaggageCreateRequest(new BigDecimal(weight), null, compartment));
    }

    // ---------- POST ----------

    // Verifica que CLERK y ADMIN registren equipaje sobre un ticket vendido
    @ParameterizedTest
    @ValueSource(strings = {"CLERK", "ADMIN"})
    void addBaggage_shouldReturn201(String role) throws Exception {
        when(baggageService.addBaggage(eq(10L), any(BaggageCreateRequest.class))).thenReturn(response());

        mvc.perform(post("/api/v1/tickets/10/baggage").with(user("staff").roles(role))
                        .contentType(MediaType.APPLICATION_JSON).content(body("25", "B")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ticketId").value(10))
                .andExpect(jsonPath("$.excessFee").value(10000.00))
                .andExpect(jsonPath("$.compartment").value("B"))
                .andExpect(jsonPath("$.tagCode").value("BAG-1"));
    }

    // Verifica 400 sin peso, con peso no positivo o con maletero inválido
    @Test
    @WithMockUser(roles = "CLERK")
    void addBaggage_shouldReturn400WhenInvalid() throws Exception {
        mvc.perform(post("/api/v1/tickets/10/baggage").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.weightKg").exists());
        mvc.perform(post("/api/v1/tickets/10/baggage").contentType(MediaType.APPLICATION_JSON).content(body("0", null)))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/tickets/10/baggage").contentType(MediaType.APPLICATION_JSON)
                        .content(body("10", "maletero trasero")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.compartment").exists());

        verifyNoInteractions(baggageService);
    }

    // Verifica 400 cuando el peso supera el máximo absoluto configurado
    @Test
    @WithMockUser(roles = "CLERK")
    void addBaggage_shouldReturn400WhenWeightExceedsMax() throws Exception {
        when(baggageService.addBaggage(eq(10L), any(BaggageCreateRequest.class)))
                .thenThrow(new BusinessException("El peso del equipaje excede el máximo permitido de 50.0 kg",
                        HttpStatus.BAD_REQUEST, "BAGGAGE_WEIGHT_EXCEEDED"));

        mvc.perform(post("/api/v1/tickets/10/baggage").contentType(MediaType.APPLICATION_JSON).content(body("60", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("equipaje")));
    }

    // Verifica 409 si el ticket ya tiene equipaje o no está vendido y 404 si no existe
    @Test
    @WithMockUser(roles = "CLERK")
    void addBaggage_shouldReturn409And404() throws Exception {
        when(baggageService.addBaggage(eq(10L), any(BaggageCreateRequest.class)))
                .thenThrow(new BusinessException("Ya tiene equipaje", HttpStatus.CONFLICT, "BAGGAGE_ALREADY_REGISTERED"));
        when(baggageService.addBaggage(eq(99L), any(BaggageCreateRequest.class)))
                .thenThrow(new ResourceNotFoundException("Ticket", 99L));

        mvc.perform(post("/api/v1/tickets/10/baggage").contentType(MediaType.APPLICATION_JSON).content(body("10", null)))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/tickets/99/baggage").contentType(MediaType.APPLICATION_JSON).content(body("10", null)))
                .andExpect(status().isNotFound());
    }

    // Verifica que el resto de roles no registre equipaje
    @ParameterizedTest
    @ValueSource(strings = {"PASSENGER", "DRIVER", "DISPATCHER"})
    void addBaggage_shouldReturn403ForOtherRoles(String role) throws Exception {
        mvc.perform(post("/api/v1/tickets/10/baggage").with(user("other").roles(role))
                        .contentType(MediaType.APPLICATION_JSON).content(body("10", null)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(baggageService);
    }

    // Verifica 401 sin autenticación
    @Test
    void addBaggage_shouldReturn401WhenAnonymous() throws Exception {
        mvc.perform(post("/api/v1/tickets/10/baggage").contentType(MediaType.APPLICATION_JSON).content(body("10", null)))
                .andExpect(status().isUnauthorized());
    }

    // ---------- GET ----------

    // Verifica la consulta del equipaje de un ticket por cualquier autenticado (el servicio limita al pasajero)
    @ParameterizedTest
    @ValueSource(strings = {"PASSENGER", "CLERK", "DRIVER"})
    void getBaggage_shouldReturn200(String role) throws Exception {
        when(baggageService.getBaggage(10L)).thenReturn(response());

        mvc.perform(get("/api/v1/tickets/10/baggage").with(user("u").roles(role)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1));
    }

    // Verifica 404 cuando el ticket no tiene equipaje
    @Test
    @WithMockUser(roles = "CLERK")
    void getBaggage_shouldReturn404WhenMissing() throws Exception {
        when(baggageService.getBaggage(10L)).thenThrow(new ResourceNotFoundException("Equipaje del ticket", 10L));

        mvc.perform(get("/api/v1/tickets/10/baggage"))
                .andExpect(status().isNotFound());
    }

    // ---------- DELETE ----------

    // Verifica que un CLERK retire el equipaje antes de abordar
    @Test
    @WithMockUser(roles = "CLERK")
    void removeBaggage_shouldReturn204() throws Exception {
        mvc.perform(delete("/api/v1/tickets/10/baggage"))
                .andExpect(status().isNoContent());

        verify(baggageService).removeBaggage(10L);
    }

    // Verifica 409 si el pasajero ya abordó
    @Test
    @WithMockUser(roles = "CLERK")
    void removeBaggage_shouldReturn409AfterBoarding() throws Exception {
        doThrow(new BusinessException("Ya abordó", HttpStatus.CONFLICT, "PASSENGER_ALREADY_BOARDED"))
                .when(baggageService).removeBaggage(10L);

        mvc.perform(delete("/api/v1/tickets/10/baggage"))
                .andExpect(status().isConflict());
    }

    // Verifica que solo CLERK retire equipaje
    @ParameterizedTest
    @ValueSource(strings = {"PASSENGER", "DRIVER", "ADMIN"})
    void removeBaggage_shouldReturn403ForOtherRoles(String role) throws Exception {
        mvc.perform(delete("/api/v1/tickets/10/baggage").with(user("other").roles(role)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(baggageService);
    }
}
