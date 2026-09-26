package com.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.dto.dispatch.Assignment.AssignmentCreateRequest;
import com.web.dto.dispatch.Assignment.AssignmentResponse;
import com.web.dto.trip.TripResponse;
import com.web.entity.Trip;
import com.web.config.CustomUserDetailsService;
import com.web.config.SecurityConfig;
import com.web.dto.dispatch.Assignment.AssignmentUpdateRequest;
import com.web.exception.BusinessException;
import com.web.exception.InvalidStateTransitionException;
import com.web.exception.ResourceNotFoundException;

import com.web.service.dispatch.AssignmentService;
import com.web.service.dispatch.BoardingService;
import com.web.util.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;


@WebMvcTest(DispatchController.class)
@Import(SecurityConfig.class)
class DispatchControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper om;

    @MockitoBean
    private AssignmentService assignmentService;

    @MockitoBean
    private BoardingService boardingService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private CustomUserDetailsService customUserDetailsService;

    // Verifica que un DISPATCHER pueda asignar un viaje a un conductor
    @Test
    @WithMockUser(roles = "DISPATCHER")
    void assignTrip_shouldReturn201() throws Exception {
        var req = new AssignmentCreateRequest(1L, 1L, 2L);
        var resp = new AssignmentResponse(
                1L, 1L, 1L, "Driver Name", "123456789",
                2L, "Dispatcher Name",
                false, false, false,
                LocalDateTime.now()
        );

        when(assignmentService.assignTrip(any())).thenReturn(resp);

        mvc.perform(post("/api/v1/trips/1/assign").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1));
    }

    // Verifica validación cuando el tripId de la URL no coincide con el del body
    @Test
    @WithMockUser(roles = "DISPATCHER")
    void assignTrip_shouldReturn400WhenTripIdMismatch() throws Exception {
        var req = new AssignmentCreateRequest(2L, 1L, 2L);

        mvc.perform(post("/api/v1/trips/1/assign").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    // Verifica que un DISPATCHER pueda abrir el abordaje de un viaje
    @Test
    @WithMockUser(roles = "DISPATCHER")
    void controlBoarding_open_shouldReturn200() throws Exception {
        var resp = new TripResponse(
                1L, 1L, "Route Name", "Origin", "Destination",
                1L, "ABC123", 40,
                LocalDate.now(), LocalDateTime.now(), null,
                Trip.TripStatus.BOARDING, 0, 0.0
        );

        when(boardingService.openBoarding(1L)).thenReturn(resp);

        mvc.perform(post("/api/v1/trips/1/boarding/open")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BOARDING"));
    }

    // Verifica que un DISPATCHER pueda cerrar el abordaje de un viaje
    @Test
    @WithMockUser(roles = "DISPATCHER")
    void controlBoarding_close_shouldReturn200() throws Exception {
        var resp = new TripResponse(
                1L, 1L, "Route Name", "Origin", "Destination",
                1L, "ABC123", 40,
                LocalDate.now(), LocalDateTime.now(), null,
                Trip.TripStatus.SCHEDULED, 0, 0.0
        );

        when(boardingService.closeBoarding(1L)).thenReturn(resp);

        mvc.perform(post("/api/v1/trips/1/boarding/close")
                        .with(csrf()))
                .andExpect(status().isOk());
    }

    // Verifica validación cuando se envía una acción inválida (ni open ni close)
    @Test
    @WithMockUser(roles = "DISPATCHER")
    void controlBoarding_shouldReturn400WhenInvalidAction() throws Exception {
        mvc.perform(post("/api/v1/trips/1/boarding/invalid")
                        .with(csrf()))
                .andExpect(status().isBadRequest());
    }

    // Verifica que un DRIVER pueda marcar un viaje como partido
    @Test
    @WithMockUser(roles = "DRIVER")
    void departTrip_shouldReturn200() throws Exception {
        var resp = new TripResponse(
                1L, 1L, "Route Name", "Origin", "Destination",
                1L, "ABC123", 40,
                LocalDate.now(), LocalDateTime.now(), null,
                Trip.TripStatus.DEPARTED, 0, 0.0
        );

        when(boardingService.departTrip(1L)).thenReturn(resp);

        mvc.perform(post("/api/v1/trips/1/depart")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DEPARTED"));
    }
}

