package com.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.config.CustomUserDetailsService;
import com.web.config.SecurityConfig;
import com.web.dto.trip.TripResponse;
import com.web.dto.trip.TripUpdateRequest;
import com.web.entity.Trip;
import com.web.exception.BusinessException;
import com.web.exception.InvalidStateTransitionException;
import com.web.exception.ResourceNotFoundException;
import com.web.service.trip.TripService;
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

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// PUT /api/v1/trips/{id}: reprogramación de viajes (ADMIN)
@WebMvcTest(TripController.class)
@Import(SecurityConfig.class)
class TripRescheduleControllerTest {

    private static final LocalDateTime DEPARTURE = LocalDate.now().plusDays(5).atTime(9, 0);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper om;

    @MockitoBean
    private TripService tripService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private CustomUserDetailsService customUserDetailsService;

    private String body() throws Exception {
        return om.writeValueAsString(new TripUpdateRequest(DEPARTURE, DEPARTURE.plusHours(4), 2L));
    }

    // Verifica que un ADMIN pueda reprogramar un viaje
    @Test
    @WithMockUser(roles = "ADMIN")
    void rescheduleTrip_shouldReturn200() throws Exception {
        var resp = new TripResponse(1L, 1L, "Ruta", "A", "B", 2L, "XYZ789", 30,
                DEPARTURE.toLocalDate(), DEPARTURE, DEPARTURE.plusHours(4), Trip.TripStatus.SCHEDULED, null, null, null, null, null, null);
        when(tripService.rescheduleTrip(eq(1L), any(TripUpdateRequest.class))).thenReturn(resp);

        mvc.perform(put("/api/v1/trips/1").contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.busId").value(2))
                .andExpect(jsonPath("$.status").value("SCHEDULED"));

        verify(tripService).rescheduleTrip(1L, new TripUpdateRequest(DEPARTURE, DEPARTURE.plusHours(4), 2L));
    }

    // Verifica 400 cuando las fechas no son coherentes
    @Test
    @WithMockUser(roles = "ADMIN")
    void rescheduleTrip_shouldReturn400WhenInvalidDates() throws Exception {
        when(tripService.rescheduleTrip(eq(1L), any())).thenThrow(new BusinessException(
                "La llegada debe ser posterior a la salida", HttpStatus.BAD_REQUEST, "INVALID_DATES"));

        mvc.perform(put("/api/v1/trips/1").contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isBadRequest());
    }

    // Verifica 400 con JSON mal formado
    @Test
    @WithMockUser(roles = "ADMIN")
    void rescheduleTrip_shouldReturn400WhenMalformedBody() throws Exception {
        mvc.perform(put("/api/v1/trips/1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"departureTime\": \"no-es-fecha\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(tripService);
    }

    // Verifica 404 cuando el viaje no existe
    @Test
    @WithMockUser(roles = "ADMIN")
    void rescheduleTrip_shouldReturn404WhenTripNotFound() throws Exception {
        when(tripService.rescheduleTrip(eq(99L), any())).thenThrow(new ResourceNotFoundException("Viaje", 99L));

        mvc.perform(put("/api/v1/trips/99").contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isNotFound());
    }

    // Verifica 409 cuando el bus nuevo está ocupado ese día
    @Test
    @WithMockUser(roles = "ADMIN")
    void rescheduleTrip_shouldReturn409WhenBusBusy() throws Exception {
        when(tripService.rescheduleTrip(eq(1L), any())).thenThrow(new BusinessException(
                "El bus ya tiene un viaje programado ese día", HttpStatus.CONFLICT, "BUS_BUSY"));

        mvc.perform(put("/api/v1/trips/1").contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("El bus ya tiene un viaje programado ese día"));
    }

    // Verifica 422 cuando el viaje no está SCHEDULED
    @Test
    @WithMockUser(roles = "ADMIN")
    void rescheduleTrip_shouldReturn422WhenNotScheduled() throws Exception {
        when(tripService.rescheduleTrip(eq(1L), any())).thenThrow(
                new InvalidStateTransitionException("Solo se puede reprogramar un viaje SCHEDULED"));

        mvc.perform(put("/api/v1/trips/1").contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isUnprocessableEntity());
    }

    // Verifica que un DISPATCHER no pueda reprogramar viajes
    @Test
    @WithMockUser(roles = "DISPATCHER")
    void rescheduleTrip_shouldReturn403ForDispatcher() throws Exception {
        mvc.perform(put("/api/v1/trips/1").contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(tripService);
    }

    // Verifica que sin autenticación responda 401
    @Test
    void rescheduleTrip_shouldReturn401WhenAnonymous() throws Exception {
        mvc.perform(put("/api/v1/trips/1").contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(tripService);
    }
}
