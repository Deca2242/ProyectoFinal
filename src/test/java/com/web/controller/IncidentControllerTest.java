package com.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.config.CustomUserDetailsService;
import com.web.config.SecurityConfig;
import com.web.dto.incident.IncidentCreateRequest;
import com.web.dto.incident.IncidentResponse;
import com.web.entity.Incident;
import com.web.exception.BusinessException;
import com.web.exception.ResourceNotFoundException;
import com.web.service.incident.IncidentService;
import com.web.util.JwtTokenProvider;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(IncidentController.class)
@Import(SecurityConfig.class)
class IncidentControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper om;

    @MockitoBean
    private IncidentService incidentService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private CustomUserDetailsService customUserDetailsService;

    private IncidentResponse response() {
        return new IncidentResponse(1L, Incident.IncidentType.VEHICLE, Incident.EntityType.TRIP, 5L,
                "Llanta pinchada", 7L, "Conductor", LocalDateTime.now());
    }

    private String body() throws Exception {
        return om.writeValueAsString(new IncidentCreateRequest(Incident.IncidentType.VEHICLE,
                Incident.EntityType.TRIP, 5L, "Llanta pinchada"));
    }

    // ---------- POST ----------

    // Verifica que el personal en operación pueda reportar incidentes
    @ParameterizedTest
    @ValueSource(strings = {"DRIVER", "DISPATCHER", "CLERK"})
    void reportIncident_shouldReturn201ForOperationalStaff(String role) throws Exception {
        when(incidentService.reportIncident(any(IncidentCreateRequest.class))).thenReturn(response());

        mvc.perform(post("/api/v1/incidents").with(user("staff").roles(role))
                        .contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("VEHICLE"))
                .andExpect(jsonPath("$.reportedById").value(7));
    }

    // Verifica la validación de los campos obligatorios
    @Test
    @WithMockUser(roles = "DRIVER")
    void reportIncident_shouldReturn400WhenMissingFields() throws Exception {
        String body = om.writeValueAsString(new IncidentCreateRequest(null, Incident.EntityType.TRIP, null, " "));

        mvc.perform(post("/api/v1/incidents").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.type").exists())
                .andExpect(jsonPath("$.validationErrors.entityId").exists())
                .andExpect(jsonPath("$.validationErrors.description").exists());
        verifyNoInteractions(incidentService);
    }

    // Verifica 400 con un tipo de incidente inexistente
    @Test
    @WithMockUser(roles = "DRIVER")
    void reportIncident_shouldReturn400WhenUnknownType() throws Exception {
        String body = "{\"type\":\"FIRE\",\"entityType\":\"TRIP\",\"entityId\":5,\"description\":\"x\"}";

        mvc.perform(post("/api/v1/incidents").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(incidentService);
    }

    // Verifica 404 cuando la entidad no existe
    @Test
    @WithMockUser(roles = "CLERK")
    void reportIncident_shouldReturn404WhenEntityNotFound() throws Exception {
        when(incidentService.reportIncident(any())).thenThrow(new ResourceNotFoundException("Viaje", 5L));

        mvc.perform(post("/api/v1/incidents").contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isNotFound());
    }

    // Verifica que un PASSENGER o un ADMIN no reporten incidentes
    @ParameterizedTest
    @ValueSource(strings = {"PASSENGER", "ADMIN"})
    void reportIncident_shouldReturn403ForOtherRoles(String role) throws Exception {
        mvc.perform(post("/api/v1/incidents").with(user("other").roles(role))
                        .contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(incidentService);
    }

    // Verifica 401 sin autenticación
    @Test
    void reportIncident_shouldReturn401WhenAnonymous() throws Exception {
        mvc.perform(post("/api/v1/incidents").contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isUnauthorized());
    }

    // ---------- GET ----------

    // Verifica la consulta con filtros por ADMIN y DISPATCHER
    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "DISPATCHER"})
    void searchIncidents_shouldReturn200WithFilters(String role) throws Exception {
        when(incidentService.searchIncidents(Incident.IncidentType.VEHICLE, Incident.EntityType.TRIP, 5L,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31))).thenReturn(List.of(response()));

        mvc.perform(get("/api/v1/incidents").with(user("viewer").roles(role))
                        .param("type", "VEHICLE").param("entityType", "TRIP").param("entityId", "5")
                        .param("from", "2026-01-01").param("to", "2026-01-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].entityId").value(5));
    }

    // Verifica que sin filtros se consulten todos
    @Test
    @WithMockUser(roles = "ADMIN")
    void searchIncidents_withoutFilters_shouldReturn200() throws Exception {
        when(incidentService.searchIncidents(null, null, null, null, null)).thenReturn(List.of());

        mvc.perform(get("/api/v1/incidents"))
                .andExpect(status().isOk());
        verify(incidentService).searchIncidents(null, null, null, null, null);
    }

    // Verifica 400 con fecha mal formada o rango invertido
    @Test
    @WithMockUser(roles = "ADMIN")
    void searchIncidents_shouldReturn400WhenInvalidDates() throws Exception {
        mvc.perform(get("/api/v1/incidents").param("from", "ayer"))
                .andExpect(status().isBadRequest());

        when(incidentService.searchIncidents(null, null, null, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 1, 1)))
                .thenThrow(new BusinessException("Rango inválido", HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE"));
        mvc.perform(get("/api/v1/incidents").param("from", "2026-02-01").param("to", "2026-01-01"))
                .andExpect(status().isBadRequest());
    }

    // Verifica que un DRIVER no pueda consultar incidentes
    @Test
    @WithMockUser(roles = "DRIVER")
    void searchIncidents_shouldReturn403ForDriver() throws Exception {
        mvc.perform(get("/api/v1/incidents"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(incidentService);
    }

    // Verifica 401 sin autenticación
    @Test
    void searchIncidents_shouldReturn401WhenAnonymous() throws Exception {
        mvc.perform(get("/api/v1/incidents"))
                .andExpect(status().isUnauthorized());
    }
}
