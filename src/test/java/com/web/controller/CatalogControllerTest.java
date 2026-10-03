package com.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.dto.catalog.Route.RouteCreateRequest;
import com.web.dto.catalog.Route.RouteDetailResponse;
import com.web.dto.catalog.Route.RouteResponse;
import com.web.dto.catalog.Route.RouteUpdateRequest;
import com.web.dto.catalog.Stop.StopCreateRequest;
import com.web.config.CustomUserDetailsService;
import com.web.config.SecurityConfig;
import com.web.exception.BusinessException;
import com.web.exception.ResourceNotFoundException;
import com.web.service.catalog.RouteService;
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
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;


// Se importa la SecurityConfig real (en lugar de desactivar los filtros) para probar las reglas por URL y @PreAuthorize
@WebMvcTest(CatalogController.class)
@Import(SecurityConfig.class)
class CatalogControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper om;

    @MockitoBean
    private RouteService routeService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private CustomUserDetailsService customUserDetailsService;

    // Verifica que cualquier usuario pueda consultar todas las rutas (público)
    @Test
    void getAllRoutes_shouldReturn200() throws Exception {
        var resp = List.of(new RouteResponse(
                1L, "R001", "Bogotá - Medellín", "Bogotá", "Medellín",
                BigDecimal.valueOf(500.0), 360, true
        ));

        when(routeService.getAllRoutes()).thenReturn(resp);

        mvc.perform(get("/api/v1/routes")
                        .with(anonymous()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1));
    }

    // Verifica que cualquier usuario pueda consultar una ruta por ID (público)
    @Test
    void getRouteById_shouldReturn200() throws Exception {
        var resp = new RouteDetailResponse(
                1L, "R001", "Bogotá - Medellín", "Bogotá", "Medellín",
                BigDecimal.valueOf(500.0), 360, true, List.of()
        );

        when(routeService.getRouteById(1L)).thenReturn(resp);

        mvc.perform(get("/api/v1/routes/1")
                        .with(anonymous()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1));
    }

    // Verifica que retorne 404 cuando la ruta no existe
    @Test
    void getRouteById_shouldReturn404WhenNotFound() throws Exception {
        when(routeService.getRouteById(99L)).thenThrow(new ResourceNotFoundException("Ruta", 99L));

        mvc.perform(get("/api/v1/routes/99")
                        .with(anonymous()))
                .andExpect(status().isNotFound());
    }

    // Verifica que cualquier usuario pueda consultar una ruta con sus paradas (público)
    @Test
    void getRouteWithStops_shouldReturn200() throws Exception {
        var resp = new RouteDetailResponse(
                1L, "R001", "Bogotá - Medellín", "Bogotá", "Medellín",
                BigDecimal.valueOf(500.0), 360, true, List.of()
        );

        when(routeService.getRouteById(1L)).thenReturn(resp);

        mvc.perform(get("/api/v1/routes/1/stops")
                        .with(anonymous()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1));
    }

    // Verifica que un ADMIN pueda crear una nueva ruta
    @Test
    @WithMockUser(roles = "ADMIN")
    void createRoute_shouldReturn201() throws Exception {
        var req = new RouteCreateRequest(
                "R001", "Bogotá - Medellín", "Bogotá", "Medellín",
                BigDecimal.valueOf(500.0), 360
        );
        var resp = new RouteResponse(
                1L, "R001", "Bogotá - Medellín", "Bogotá", "Medellín",
                BigDecimal.valueOf(500.0), 360, true
        );

        when(routeService.createRoute(any())).thenReturn(resp);

        mvc.perform(post("/api/v1/routes").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1));
    }

    // Verifica que un ADMIN pueda actualizar los datos de una ruta
    @Test
    @WithMockUser(roles = "ADMIN")
    void updateRoute_shouldReturn200() throws Exception {
        var req = new RouteUpdateRequest(
                "Bogotá - Cali", BigDecimal.valueOf(600.0), 420, null
        );
        var resp = new RouteResponse(
                1L, "R001", "Bogotá - Cali", "Bogotá", "Cali",
                BigDecimal.valueOf(600.0), 420, true
        );

        when(routeService.updateRoute(1L, req)).thenReturn(resp);

        mvc.perform(put("/api/v1/routes/1").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Bogotá - Cali"));
    }

    // Verifica que un ADMIN pueda eliminar una ruta
    @Test
    @WithMockUser(roles = "ADMIN")
    void deleteRoute_shouldReturn204() throws Exception {
        mvc.perform(delete("/api/v1/routes/1")
                        .with(csrf()))
                .andExpect(status().isNoContent());
    }

    // Verifica que un ADMIN pueda agregar una parada a una ruta
    @Test
    @WithMockUser(roles = "ADMIN")
    void addStop_shouldReturn201() throws Exception {
        var req = new StopCreateRequest(1L, "Pereira", 2, null, null);
        var resp = new RouteDetailResponse(
                1L, "R001", "Bogotá - Medellín", "Bogotá", "Medellín",
                BigDecimal.valueOf(500.0), 360, true, List.of()
        );

        when(routeService.addStop(1L, req)).thenReturn(resp);

        mvc.perform(post("/api/v1/routes/1/stops").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1));
    }

    // Verifica que un ADMIN pueda eliminar una parada de una ruta
    @Test
    @WithMockUser(roles = "ADMIN")
    void removeStop_shouldReturn204() throws Exception {
        mvc.perform(delete("/api/v1/routes/1/stops/1")
                        .with(csrf()))
                .andExpect(status().isNoContent());
    }

    // ---------------------------------------------------------------------
    // Casos adicionales: validación, seguridad y errores propagados
    // ---------------------------------------------------------------------

    private RouteCreateRequest validRouteRequest() {
        return new RouteCreateRequest(
                "R001", "Bogotá - Medellín", "Bogotá", "Medellín",
                BigDecimal.valueOf(500.0), 360
        );
    }

    // Verifica que la consulta de rutas sea pública sin ningún usuario en el contexto
    @Test
    void getAllRoutes_shouldBePublicWithoutAuthentication() throws Exception {
        when(routeService.getAllRoutes()).thenReturn(List.of());

        mvc.perform(get("/api/v1/routes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    // Verifica que un id no numérico en la URL devuelva 400
    @Test
    void getRouteById_shouldReturn400WhenIdNotNumeric() throws Exception {
        mvc.perform(get("/api/v1/routes/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        verifyNoInteractions(routeService);
    }

    // Verifica que retorne 404 al consultar las paradas de una ruta inexistente
    @Test
    void getRouteWithStops_shouldReturn404WhenNotFound() throws Exception {
        when(routeService.getRouteById(99L)).thenThrow(new ResourceNotFoundException("Ruta", 99L));

        mvc.perform(get("/api/v1/routes/99/stops"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Ruta con id 99 no encontrado"));
    }

    // Verifica que crear una ruta con campos vacíos devuelva 400 con los errores por campo
    @Test
    @WithMockUser(roles = "ADMIN")
    void createRoute_shouldReturn400WhenInvalid() throws Exception {
        var req = new RouteCreateRequest("", "", null, null, null, null);

        mvc.perform(post("/api/v1/routes").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.code").exists())
                .andExpect(jsonPath("$.validationErrors.name").exists())
                .andExpect(jsonPath("$.validationErrors.origin").exists())
                .andExpect(jsonPath("$.validationErrors.destination").exists())
                .andExpect(jsonPath("$.validationErrors.distanceKm").exists())
                .andExpect(jsonPath("$.validationErrors.durationMin").exists());

        verifyNoInteractions(routeService);
    }

    // Verifica que un JSON mal formado al crear una ruta devuelva 400
    @Test
    @WithMockUser(roles = "ADMIN")
    void createRoute_shouldReturn400WhenMalformedJson() throws Exception {
        mvc.perform(post("/api/v1/routes").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\": \"R001\""))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(routeService);
    }

    // Verifica que crear una ruta sin autenticación devuelva 401
    @Test
    void createRoute_shouldReturn401WhenNotAuthenticated() throws Exception {
        mvc.perform(post("/api/v1/routes").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(validRouteRequest())))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(routeService);
    }

    // Verifica que un PASSENGER no pueda crear rutas (403)
    @Test
    @WithMockUser(roles = "PASSENGER")
    void createRoute_shouldReturn403WhenNotAdmin() throws Exception {
        mvc.perform(post("/api/v1/routes").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(validRouteRequest())))
                .andExpect(status().isForbidden());

        verifyNoInteractions(routeService);
    }

    // Verifica que un código de ruta duplicado devuelva 409
    @Test
    @WithMockUser(roles = "ADMIN")
    void createRoute_shouldReturn409WhenCodeExists() throws Exception {
        when(routeService.createRoute(any())).thenThrow(
                new BusinessException("Ya existe una ruta con el código: R001", HttpStatus.CONFLICT, "ROUTE_CODE_EXISTS"));

        mvc.perform(post("/api/v1/routes").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(validRouteRequest())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Ya existe una ruta con el código: R001"));
    }

    // Verifica que una violación de integridad al crear la ruta devuelva 409
    @Test
    @WithMockUser(roles = "ADMIN")
    void createRoute_shouldReturn409WhenDataIntegrityViolation() throws Exception {
        when(routeService.createRoute(any())).thenThrow(new DataIntegrityViolationException("routes_code_key"));

        mvc.perform(post("/api/v1/routes").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(validRouteRequest())))
                .andExpect(status().isConflict());
    }

    // Verifica que actualizar una ruta inexistente devuelva 404
    @Test
    @WithMockUser(roles = "ADMIN")
    void updateRoute_shouldReturn404WhenNotFound() throws Exception {
        var req = new RouteUpdateRequest("Bogotá - Cali", null, null, null);

        when(routeService.updateRoute(99L, req)).thenThrow(new ResourceNotFoundException("Ruta", 99L));

        mvc.perform(put("/api/v1/routes/99").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isNotFound());
    }

    // Verifica que un DISPATCHER no pueda actualizar rutas (403)
    @Test
    @WithMockUser(roles = "DISPATCHER")
    void updateRoute_shouldReturn403WhenNotAdmin() throws Exception {
        var req = new RouteUpdateRequest("Bogotá - Cali", null, null, null);

        mvc.perform(put("/api/v1/routes/1").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(routeService);
    }

    // Verifica que actualizar una ruta sin autenticación devuelva 401
    @Test
    void updateRoute_shouldReturn401WhenNotAuthenticated() throws Exception {
        var req = new RouteUpdateRequest("Bogotá - Cali", null, null, null);

        mvc.perform(put("/api/v1/routes/1").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isUnauthorized());
    }

    // Verifica que eliminar una ruta invoque al servicio con el id de la URL
    @Test
    @WithMockUser(roles = "ADMIN")
    void deleteRoute_shouldCallService() throws Exception {
        mvc.perform(delete("/api/v1/routes/5"))
                .andExpect(status().isNoContent());

        verify(routeService).deleteRoute(5L);
    }

    // Verifica que eliminar una ruta con viajes programados devuelva 400
    @Test
    @WithMockUser(roles = "ADMIN")
    void deleteRoute_shouldReturn400WhenRouteHasTrips() throws Exception {
        doThrow(new BusinessException("No se puede eliminar la ruta porque tiene viajes programados",
                HttpStatus.BAD_REQUEST, "ROUTE_HAS_TRIPS")).when(routeService).deleteRoute(1L);

        mvc.perform(delete("/api/v1/routes/1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("No se puede eliminar la ruta porque tiene viajes programados"));
    }

    // Verifica que eliminar una ruta inexistente devuelva 404
    @Test
    @WithMockUser(roles = "ADMIN")
    void deleteRoute_shouldReturn404WhenNotFound() throws Exception {
        doThrow(new ResourceNotFoundException("Ruta", 99L)).when(routeService).deleteRoute(99L);

        mvc.perform(delete("/api/v1/routes/99"))
                .andExpect(status().isNotFound());
    }

    // Verifica que un CLERK no pueda eliminar rutas (403)
    @Test
    @WithMockUser(roles = "CLERK")
    void deleteRoute_shouldReturn403WhenNotAdmin() throws Exception {
        mvc.perform(delete("/api/v1/routes/1"))
                .andExpect(status().isForbidden());

        verify(routeService, never()).deleteRoute(any());
    }

    // Verifica que eliminar una ruta sin autenticación devuelva 401
    @Test
    void deleteRoute_shouldReturn401WhenNotAuthenticated() throws Exception {
        mvc.perform(delete("/api/v1/routes/1"))
                .andExpect(status().isUnauthorized());
    }

    // Verifica que agregar una parada con datos inválidos devuelva 400
    @Test
    @WithMockUser(roles = "ADMIN")
    void addStop_shouldReturn400WhenInvalid() throws Exception {
        var req = new StopCreateRequest(null, "", null, null, null);

        mvc.perform(post("/api/v1/routes/1/stops").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.routeId").exists())
                .andExpect(jsonPath("$.validationErrors.name").exists())
                .andExpect(jsonPath("$.validationErrors.order").exists());

        verifyNoInteractions(routeService);
    }

    // Verifica que un rol distinto de ADMIN reciba 403 desde @PreAuthorize (no 500) al agregar paradas
    @Test
    @WithMockUser(roles = "DISPATCHER")
    void addStop_shouldReturn403WhenNotAdmin() throws Exception {
        var req = new StopCreateRequest(1L, "Pereira", 2, null, null);

        mvc.perform(post("/api/v1/routes/1/stops").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").value("No tienes los permisos suficientes para realizar esta acción."));

        verifyNoInteractions(routeService);
    }

    // Verifica que agregar una parada sin autenticación devuelva 401
    @Test
    void addStop_shouldReturn401WhenNotAuthenticated() throws Exception {
        var req = new StopCreateRequest(1L, "Pereira", 2, null, null);

        mvc.perform(post("/api/v1/routes/1/stops").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isUnauthorized());
    }

    // Verifica que agregar una parada a una ruta inexistente devuelva 404
    @Test
    @WithMockUser(roles = "ADMIN")
    void addStop_shouldReturn404WhenRouteNotFound() throws Exception {
        var req = new StopCreateRequest(99L, "Pereira", 2, null, null);

        when(routeService.addStop(99L, req)).thenThrow(new ResourceNotFoundException("Ruta", 99L));

        mvc.perform(post("/api/v1/routes/99/stops").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isNotFound());
    }

    // Verifica que un orden de parada repetido devuelva 409
    @Test
    @WithMockUser(roles = "ADMIN")
    void addStop_shouldReturn409WhenOrderExists() throws Exception {
        var req = new StopCreateRequest(1L, "Pereira", 2, null, null);

        when(routeService.addStop(1L, req)).thenThrow(new BusinessException(
                "Ya existe una parada con el orden 2 en esta ruta", HttpStatus.CONFLICT, "STOP_ORDER_EXISTS"));

        mvc.perform(post("/api/v1/routes/1/stops").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isConflict());
    }

    // Verifica que eliminar una parada que no pertenece a la ruta devuelva 400
    @Test
    @WithMockUser(roles = "ADMIN")
    void removeStop_shouldReturn400WhenStopNotInRoute() throws Exception {
        doThrow(new BusinessException("La parada no pertenece a esta ruta", HttpStatus.BAD_REQUEST, "STOP_ROUTE_MISMATCH"))
                .when(routeService).removeStop(1L, 9L);

        mvc.perform(delete("/api/v1/routes/1/stops/9"))
                .andExpect(status().isBadRequest());
    }

    // Verifica que eliminar una parada inexistente devuelva 404
    @Test
    @WithMockUser(roles = "ADMIN")
    void removeStop_shouldReturn404WhenNotFound() throws Exception {
        doThrow(new ResourceNotFoundException("Parada", 99L)).when(routeService).removeStop(1L, 99L);

        mvc.perform(delete("/api/v1/routes/1/stops/99"))
                .andExpect(status().isNotFound());
    }

    // Verifica que un DRIVER no pueda eliminar paradas (403)
    @Test
    @WithMockUser(roles = "DRIVER")
    void removeStop_shouldReturn403WhenNotAdmin() throws Exception {
        mvc.perform(delete("/api/v1/routes/1/stops/1"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(routeService);
    }

    // Verifica que eliminar una parada sin autenticación devuelva 401
    @Test
    void removeStop_shouldReturn401WhenNotAuthenticated() throws Exception {
        mvc.perform(delete("/api/v1/routes/1/stops/1"))
                .andExpect(status().isUnauthorized());
    }
}

