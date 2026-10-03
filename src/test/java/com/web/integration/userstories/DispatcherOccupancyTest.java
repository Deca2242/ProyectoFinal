package com.web.integration.userstories;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.config.CustomUserDetailsService;
import com.web.config.JwtAuthenticationFilter;
import com.web.controller.TripController;
import com.web.dto.trip.SeatStatusResponse;
import com.web.dto.trip.TripDetailResponse;
import com.web.entity.Trip;
import com.web.service.trip.TripService;
import com.web.util.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = TripController.class)
@AutoConfigureMockMvc(addFilters = false)
class DispatcherOccupancyTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper om;

    @MockitoBean
    private TripService tripService;

    @MockitoBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private CustomUserDetailsService customUserDetailsService;

    // Verifica ocupación por tramo para el despachador
    @Test
    @WithMockUser(roles = "DISPATCHER")
    void getSeatAvailability_shouldReturnOccupancyBySegment() throws Exception {
        // Given: Ocupación por tramo específico
        var seats = List.of(
                new SeatStatusResponse(1, true, "AVAILABLE"),
                new SeatStatusResponse(2, false, "OCCUPIED"),
                new SeatStatusResponse(3, false, "OCCUPIED"),
                new SeatStatusResponse(4, true, "AVAILABLE")
        );

        when(tripService.getSeatAvailability(1L, 1L, 2L)).thenReturn(seats);

        // When & Then
        mvc.perform(get("/api/v1/trips/1/seats")
                        .param("fromStopId", "1")
                        .param("toStopId", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].available").value(true))
                .andExpect(jsonPath("$[1].available").value(false))
                .andExpect(jsonPath("$[2].available").value(false))
                .andExpect(jsonPath("$[3].available").value(true));
    }

    // Verifica que el detalle incluya porcentaje de ocupación
    @Test
    @WithMockUser(roles = "DISPATCHER")
    void getTripDetail_shouldIncludeOccupancyRate() throws Exception {
        // Given: Detalle de viaje con tasa de ocupación
        var busResponse = new com.web.dto.catalog.Bus.BusResponse(1L, "ABC123", 40, null, com.web.entity.Bus.BusStatus.ACTIVE);
        var detail = new TripDetailResponse(
                1L, null, busResponse,
                LocalDate.now().plusDays(1), LocalDateTime.now().plusDays(1).plusHours(8), null,
                Trip.TripStatus.SCHEDULED, null, 30, 10, 75.0, List.of()
        );

        when(tripService.getTripById(1L)).thenReturn(detail);

        // When & Then
        mvc.perform(get("/api/v1/trips/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.occupancyPercentage").value(75.0))
                .andExpect(jsonPath("$.soldSeats").value(30))
                .andExpect(jsonPath("$.availableSeats").value(10))
                .andExpect(jsonPath("$.bus.capacity").value(40));
    }

    // Verifica comparación de ocupación entre múltiples tramos
    @Test
    @WithMockUser(roles = "DISPATCHER")
    void monitorMultipleSegments_shouldShowDifferentOccupancy() throws Exception {
        // Given: Diferentes tramos con ocupación diferente
        var segment1 = List.of(
                new SeatStatusResponse(1, false, "OCCUPIED"),
                new SeatStatusResponse(2, false, "OCCUPIED"),
                new SeatStatusResponse(3, true, "AVAILABLE")
        );
        var segment2 = List.of(
                new SeatStatusResponse(1, true, "AVAILABLE"),
                new SeatStatusResponse(2, true, "AVAILABLE"),
                new SeatStatusResponse(3, true, "AVAILABLE")
        );

        when(tripService.getSeatAvailability(1L, 1L, 2L)).thenReturn(segment1);
        when(tripService.getSeatAvailability(1L, 2L, 3L)).thenReturn(segment2);

        // When & Then: Segmento 1 tiene mayor ocupación
        mvc.perform(get("/api/v1/trips/1/seats")
                        .param("fromStopId", "1")
                        .param("toStopId", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].available").value(false))
                .andExpect(jsonPath("$[1].available").value(false));

        // Segmento 2 tiene menor ocupación
        mvc.perform(get("/api/v1/trips/1/seats")
                        .param("fromStopId", "2")
                        .param("toStopId", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].available").value(true))
                .andExpect(jsonPath("$[1].available").value(true));
    }
}

