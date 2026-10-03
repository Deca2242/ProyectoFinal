package com.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.config.CustomUserDetailsService;
import com.web.config.SecurityConfig;
import com.web.dto.incident.IncidentCreateRequest;
import com.web.dto.incident.IncidentResponse;
import com.web.entity.Incident;
import com.web.exception.BusinessException;
import com.web.exception.InvalidStateTransitionException;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
                "Llanta pinchada", 7L, "Conductor", LocalDateTime.now(), Incident.IncidentStatus.OPEN, null);
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
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), Incident.IncidentStatus.OPEN, null))
                .thenReturn(List.of(response()));

        mvc.perform(get("/api/v1/incidents").with(user("viewer").roles(role))
                        .param("type", "VEHICLE").param("entityType", "TRIP").param("entityId", "5")
                        .param("from", "2026-01-01").param("to", "2026-01-31").param("status", "OPEN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].entityId").value(5))
                .andExpect(jsonPath("$[0].status").value("OPEN"));
    }

    // Verifica que sin filtros se consulten todos
    @Test
    @WithMockUser(roles = "ADMIN")
    void searchIncidents_withoutFilters_shouldReturn200() throws Exception {
        when(incidentService.searchIncidents(null, null, null, null, null, null, null)).thenReturn(List.of());

        mvc.perform(get("/api/v1/incidents"))
                .andExpect(status().isOk());
        verify(incidentService).searchIncidents(null, null, null, null, null, null, null);
    }

    // Verifica 400 con fecha mal formada o rango invertido
    @Test
    @WithMockUser(roles = "ADMIN")
    void searchIncidents_shouldReturn400WhenInvalidDates() throws Exception {
        mvc.perform(get("/api/v1/incidents").param("from", "ayer"))
                .andExpect(status().isBadRequest());

        when(incidentService.searchIncidents(null, null, null, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 1, 1), null, null))
                .thenThrow(new BusinessException("Rango inválido", HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE"));
        mvc.perform(get("/api/v1/incidents").param("from", "2026-02-01").param("to", "2026-01-01"))
                .andExpect(status().isBadRequest());
    }

    // Verifica que un DRIVER sin reportedBy=me reciba el 403 del servicio (solo ve los suyos)
    @Test
    @WithMockUser(roles = "DRIVER")
    void searchIncidents_shouldReturn403ForDriverWithoutReportedByMe() throws Exception {
        when(incidentService.searchIncidents(null, null, null, null, null, null, null))
                .thenThrow(new BusinessException("Solo los suyos", HttpStatus.FORBIDDEN, "INCIDENTS_ONLY_OWN"));

        mvc.perform(get("/api/v1/incidents"))
                .andExpect(status().isForbidden());
    }

    // Verifica que CLERK y DRIVER consulten sus incidentes con reportedBy=me
    @ParameterizedTest
    @ValueSource(strings = {"CLERK", "DRIVER"})
    void searchIncidents_shouldReturn200WithReportedByMe(String role) throws Exception {
        when(incidentService.searchIncidents(null, null, null, null, null, null, "me")).thenReturn(List.of(response()));

        mvc.perform(get("/api/v1/incidents").with(user("staff").roles(role)).param("reportedBy", "me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].reportedById").value(7));
    }

    // Verifica 400 con un reportedBy distinto de "me" y con un estado inexistente
    @Test
    @WithMockUser(roles = "ADMIN")
    void searchIncidents_shouldReturn400WhenInvalidReportedByOrStatus() throws Exception {
        when(incidentService.searchIncidents(null, null, null, null, null, null, "7"))
                .thenThrow(new BusinessException("Solo 'me'", HttpStatus.BAD_REQUEST, "INVALID_REPORTED_BY"));

        mvc.perform(get("/api/v1/incidents").param("reportedBy", "7"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/incidents").param("status", "CLOSED"))
                .andExpect(status().isBadRequest());
    }

    // Verifica que un PASSENGER no pueda consultar incidentes
    @Test
    @WithMockUser(roles = "PASSENGER")
    void searchIncidents_shouldReturn403ForPassenger() throws Exception {
        mvc.perform(get("/api/v1/incidents").param("reportedBy", "me"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(incidentService);
    }

    // Verifica 401 sin autenticación
    @Test
    void searchIncidents_shouldReturn401WhenAnonymous() throws Exception {
        mvc.perform(get("/api/v1/incidents"))
                .andExpect(status().isUnauthorized());
    }

    // ---------- GET /{id} ----------

    // Verifica que el personal pueda ver el detalle (el servicio limita CLERK/DRIVER a los suyos)
    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "DISPATCHER", "CLERK", "DRIVER"})
    void getIncident_shouldReturn200ForStaff(String role) throws Exception {
        when(incidentService.getIncident(1L)).thenReturn(response());

        mvc.perform(get("/api/v1/incidents/1").with(user("staff").roles(role)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1));
    }

    // Verifica 403 cuando un CLERK pide un incidente ajeno y 404 si no existe
    @Test
    @WithMockUser(roles = "CLERK")
    void getIncident_shouldReturn403WhenNotReporterAnd404WhenMissing() throws Exception {
        when(incidentService.getIncident(1L))
                .thenThrow(new BusinessException("Ajeno", HttpStatus.FORBIDDEN, "INCIDENTS_ONLY_OWN"));
        when(incidentService.getIncident(99L)).thenThrow(new ResourceNotFoundException("Incidente", 99L));

        mvc.perform(get("/api/v1/incidents/1")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/incidents/99")).andExpect(status().isNotFound());
    }

    // Verifica que un PASSENGER no vea incidentes
    @Test
    @WithMockUser(roles = "PASSENGER")
    void getIncident_shouldReturn403ForPassenger() throws Exception {
        mvc.perform(get("/api/v1/incidents/1")).andExpect(status().isForbidden());
        verifyNoInteractions(incidentService);
    }

    // ---------- PATCH /{id}/resolve ----------

    // Verifica que ADMIN y DISPATCHER resuelvan incidentes
    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "DISPATCHER"})
    void resolveIncident_shouldReturn200ForAdminAndDispatcher(String role) throws Exception {
        IncidentResponse resolved = new IncidentResponse(1L, Incident.IncidentType.VEHICLE, Incident.EntityType.TRIP,
                5L, "Llanta pinchada", 7L, "Conductor", LocalDateTime.now(), Incident.IncidentStatus.RESOLVED,
                LocalDateTime.now());
        when(incidentService.resolveIncident(1L)).thenReturn(resolved);

        mvc.perform(patch("/api/v1/incidents/1/resolve").with(user("boss").roles(role)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.resolvedAt").isNotEmpty());
    }

    // Verifica que CLERK y DRIVER no resuelvan incidentes
    @ParameterizedTest
    @ValueSource(strings = {"CLERK", "DRIVER", "PASSENGER"})
    void resolveIncident_shouldReturn403ForOtherRoles(String role) throws Exception {
        mvc.perform(patch("/api/v1/incidents/1/resolve").with(user("staff").roles(role)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(incidentService);
    }

    // Verifica 422 al resolver un incidente ya resuelto
    @Test
    @WithMockUser(roles = "DISPATCHER")
    void resolveIncident_shouldReturn422WhenAlreadyResolved() throws Exception {
        when(incidentService.resolveIncident(1L))
                .thenThrow(new InvalidStateTransitionException("RESOLVED", "RESOLVED"));

        mvc.perform(patch("/api/v1/incidents/1/resolve"))
                .andExpect(status().isUnprocessableEntity());
    }
}
