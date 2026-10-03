package com.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.dto.trip.SeatAvailabilityResponse;
import com.web.dto.trip.SeatStatusResponse;
import com.web.dto.trip.SegmentOccupancyResponse;
import com.web.dto.trip.TripCreateRequest;
import com.web.dto.trip.TripDetailResponse;
import com.web.dto.trip.TripResponse;
import com.web.config.CustomUserDetailsService;
import com.web.config.SecurityConfig;
import com.web.entity.Trip;
import com.web.exception.InvalidSegmentException;
import com.web.exception.InvalidStateTransitionException;
import com.web.exception.ResourceNotFoundException;
import com.web.service.trip.TripService;
import com.web.util.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;

import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(TripController.class)
@Import(SecurityConfig.class)
class TripControllerTest {

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

    // Verifica que cualquier usuario pueda buscar viajes por ruta y fecha (público)
    @Test
    void searchTrips_shouldReturn200() throws Exception {
        var resp = List.of(new TripResponse(
                1L, 1L, "Route Name", "Origin", "Destination",
                1L, "ABC123", 40,
                LocalDate.now(), LocalDateTime.now(), null,
                Trip.TripStatus.SCHEDULED, 0, 0.0, null, null, null, null
        ));

        when(tripService.searchTrips(1L, LocalDate.now(), false)).thenReturn(resp);

        mvc.perform(get("/api/v1/trips")
                        .with(anonymous())
                        .param("routeId", "1")
                        .param("date", LocalDate.now().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1));
    }

    // Verifica validación cuando faltan parámetros de búsqueda
    @Test
    void searchTrips_shouldReturn400WhenMissingParams() throws Exception {
        mvc.perform(get("/api/v1/trips")
                        .with(anonymous()))
                .andExpect(status().isBadRequest());
    }

    // Verifica que cualquier usuario pueda consultar un viaje por ID (público)
    @Test
    void getTripById_shouldReturn200() throws Exception {
        var resp = new TripDetailResponse(
                1L, null, null,
                LocalDate.now(), LocalDateTime.now(), null,
                Trip.TripStatus.SCHEDULED, null, 0, 40, 0.0, List.of(), null, null, null
        );

        when(tripService.getTripById(1L)).thenReturn(resp);

        mvc.perform(get("/api/v1/trips/1")
                        .with(anonymous()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1));
    }

    // Verifica que retorne 404 cuando el viaje no existe
    @Test
    void getTripById_shouldReturn404WhenNotFound() throws Exception {
        when(tripService.getTripById(99L)).thenThrow(new ResourceNotFoundException("Viaje", 99L));

        mvc.perform(get("/api/v1/trips/99")
                        .with(anonymous()))
                .andExpect(status().isNotFound());
    }

    // Verifica que cualquier usuario pueda consultar disponibilidad de asientos (público)
    @Test
    void getSeatAvailability_shouldReturn200() throws Exception {
        var resp = new SeatAvailabilityResponse(1L, 1L, 2L, 1, 1,
                List.of(new SeatStatusResponse(1, true, "AVAILABLE", "PREFERENTIAL")));

        when(tripService.getSeatAvailability(1L, 1L, 2L)).thenReturn(resp);

        mvc.perform(get("/api/v1/trips/1/seats")
                        .with(anonymous())
                        .param("fromStopId", "1")
                        .param("toStopId", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tripId").value(1))
                .andExpect(jsonPath("$.totalSeats").value(1))
                .andExpect(jsonPath("$.availableSeats").value(1))
                .andExpect(jsonPath("$.seats[0].seatNumber").value(1))
                .andExpect(jsonPath("$.seats[0].seatType").value("PREFERENTIAL"));
    }

    // Verifica que un ADMIN pueda crear un nuevo viaje
    @Test
    @WithMockUser(roles = "ADMIN")
    void createTrip_shouldReturn201() throws Exception {
        var req = new TripCreateRequest(
                1L, 1L, LocalDate.now().plusDays(1),
                LocalDateTime.now().plusDays(1).plusHours(8),
                LocalDateTime.now().plusDays(1).plusHours(12)
        );
        var resp = new TripResponse(
                1L, 1L, "Route Name", "Origin", "Destination",
                1L, "ABC123", 40,
                LocalDate.now().plusDays(1), LocalDateTime.now().plusDays(1).plusHours(8), null,
                Trip.TripStatus.SCHEDULED, 0, 0.0, null, null, null, null
        );

        when(tripService.createTrip(any())).thenReturn(resp);

        mvc.perform(post("/api/v1/trips")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1));
    }

    // Verifica que un ADMIN pueda actualizar el estado de un viaje
    @Test
    @WithMockUser(roles = "ADMIN")
    void updateTripStatus_shouldReturn200() throws Exception {
        var resp = new TripResponse(
                1L, 1L, "Route Name", "Origin", "Destination",
                1L, "ABC123", 40,
                LocalDate.now(), LocalDateTime.now(), null,
                Trip.TripStatus.BOARDING, 0, 0.0, null, null, null, null
        );

        when(tripService.updateTripStatus(1L, Trip.TripStatus.BOARDING)).thenReturn(resp);

        mvc.perform(put("/api/v1/trips/1/status")
                        .with(csrf())
                        .param("status", "BOARDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BOARDING"));
    }

    // Verifica que un ADMIN pueda cancelar un viaje
    @Test
    @WithMockUser(roles = "ADMIN")
    void cancelTrip_shouldReturn204() throws Exception {
        mvc.perform(delete("/api/v1/trips/1")
                        .with(csrf()))
                .andExpect(status().isNoContent());
    }

    private TripResponse trip(Trip.TripStatus status) {
        return new TripResponse(
                1L, 1L, "Route Name", "Origin", "Destination",
                1L, "ABC123", 40,
                LocalDate.now(), LocalDateTime.now(), null,
                status, 0, 0.0, null, null, null, null
        );
    }

    private TripCreateRequest validCreateRequest() {
        return new TripCreateRequest(
                1L, 1L, LocalDate.now().plusDays(1),
                LocalDateTime.now().plusDays(1).plusHours(8),
                LocalDateTime.now().plusDays(1).plusHours(12)
        );
    }

    // GET /trips

    // Verifica que se pueda buscar solo por fecha
    @Test
    void searchTrips_shouldReturn200WithOnlyDate() throws Exception {
        LocalDate date = LocalDate.of(2026, 10, 1);
        when(tripService.searchTrips(null, date, false)).thenReturn(List.of(trip(Trip.TripStatus.SCHEDULED)));

        mvc.perform(get("/api/v1/trips")
                        .with(anonymous())
                        .param("date", "2026-10-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("SCHEDULED"));
    }

    // Verifica que se pueda buscar solo por ruta
    @Test
    void searchTrips_shouldReturn200WithOnlyRouteId() throws Exception {
        when(tripService.searchTrips(1L, null, false)).thenReturn(List.of());

        mvc.perform(get("/api/v1/trips")
                        .with(anonymous())
                        .param("routeId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    // Verifica que el parámetro includeAll llegue al servicio (el servicio decide si el rol puede usarlo)
    @Test
    @WithMockUser(roles = "ADMIN")
    void searchTrips_shouldPassIncludeAllToService() throws Exception {
        when(tripService.searchTrips(1L, null, true)).thenReturn(List.of(trip(Trip.TripStatus.CANCELLED)));

        mvc.perform(get("/api/v1/trips")
                        .param("routeId", "1")
                        .param("includeAll", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("CANCELLED"));

        verify(tripService).searchTrips(1L, null, true);
    }

    // Verifica que una fecha con formato inválido devuelva 400
    @Test
    void searchTrips_shouldReturn400WhenDateIsInvalid() throws Exception {
        mvc.perform(get("/api/v1/trips")
                        .with(anonymous())
                        .param("date", "01/10/2026"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(tripService);
    }

    // Verifica que un routeId no numérico devuelva 400
    @Test
    void searchTrips_shouldReturn400WhenRouteIdIsNotNumeric() throws Exception {
        mvc.perform(get("/api/v1/trips")
                        .with(anonymous())
                        .param("routeId", "uno"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(tripService);
    }

    // GET /trips/{id}

    // Verifica que un id no numérico devuelva 400
    @Test
    void getTripById_shouldReturn400WhenIdIsNotNumeric() throws Exception {
        mvc.perform(get("/api/v1/trips/abc")
                        .with(anonymous()))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(tripService);
    }

    // GET /trips/{id}/seats

    // Verifica que falte un parámetro obligatorio del tramo
    @Test
    void getSeatAvailability_shouldReturn400WhenToStopIdMissing() throws Exception {
        mvc.perform(get("/api/v1/trips/1/seats")
                        .with(anonymous())
                        .param("fromStopId", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Parámetro inválido o faltante en la petición"));

        verifyNoInteractions(tripService);
    }

    // Verifica que un tramo inválido devuelva 400
    @Test
    void getSeatAvailability_shouldReturn400WhenSegmentInvalid() throws Exception {
        when(tripService.getSeatAvailability(1L, 2L, 1L))
                .thenThrow(new InvalidSegmentException("La parada de origen debe ser anterior a la de destino"));

        mvc.perform(get("/api/v1/trips/1/seats")
                        .with(anonymous())
                        .param("fromStopId", "2")
                        .param("toStopId", "1"))
                .andExpect(status().isBadRequest());
    }

    // Verifica que retorne 404 cuando el viaje no existe
    @Test
    void getSeatAvailability_shouldReturn404WhenTripNotFound() throws Exception {
        when(tripService.getSeatAvailability(99L, 1L, 2L)).thenThrow(new ResourceNotFoundException("Viaje", 99L));

        mvc.perform(get("/api/v1/trips/99/seats")
                        .with(anonymous())
                        .param("fromStopId", "1")
                        .param("toStopId", "2"))
                .andExpect(status().isNotFound());
    }

    // Verifica que el mapa de sillas de un viaje cancelado devuelva 422
    @Test
    void getSeatAvailability_shouldReturn422WhenTripCancelled() throws Exception {
        when(tripService.getSeatAvailability(1L, 1L, 2L))
                .thenThrow(new InvalidStateTransitionException("No hay mapa de sillas para un viaje en estado CANCELLED"));

        mvc.perform(get("/api/v1/trips/1/seats")
                        .with(anonymous())
                        .param("fromStopId", "1")
                        .param("toStopId", "2"))
                .andExpect(status().isUnprocessableEntity());
    }

    // GET /trips/{id}/occupancy

    // Verifica que un DISPATCHER vea la ocupación por tramos
    @Test
    @WithMockUser(roles = "DISPATCHER")
    void getOccupancy_shouldReturn200ForDispatcher() throws Exception {
        when(tripService.getOccupancyBySegment(1L)).thenReturn(List.of(
                new SegmentOccupancyResponse(10L, "A", 11L, "B", 1, 40, 2.5),
                new SegmentOccupancyResponse(11L, "B", 12L, "C", 0, 40, 0.0)));

        mvc.perform(get("/api/v1/trips/1/occupancy"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].fromStopName").value("A"))
                .andExpect(jsonPath("$[0].soldSeats").value(1))
                .andExpect(jsonPath("$[1].occupancyPercentage").value(0.0));
    }

    // Verifica que un ADMIN también pueda consultar la ocupación por tramos
    @Test
    @WithMockUser(roles = "ADMIN")
    void getOccupancy_shouldReturn200ForAdmin() throws Exception {
        when(tripService.getOccupancyBySegment(1L)).thenReturn(List.of());

        mvc.perform(get("/api/v1/trips/1/occupancy"))
                .andExpect(status().isOk());
    }

    // Verifica que un PASSENGER no pueda consultar la ocupación por tramos
    @Test
    @WithMockUser(roles = "PASSENGER")
    void getOccupancy_shouldReturn403ForPassenger() throws Exception {
        mvc.perform(get("/api/v1/trips/1/occupancy"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(tripService);
    }

    // Verifica que la ocupación por tramos no sea pública
    @Test
    void getOccupancy_shouldReturn401WhenAnonymous() throws Exception {
        mvc.perform(get("/api/v1/trips/1/occupancy"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(tripService);
    }

    // POST /trips

    // Verifica que arrivalEta sea opcional (se calcula con la duración de la ruta)
    @Test
    @WithMockUser(roles = "ADMIN")
    void createTrip_shouldReturn201WithoutArrivalEta() throws Exception {
        when(tripService.createTrip(any())).thenReturn(trip(Trip.TripStatus.SCHEDULED));

        mvc.perform(post("/api/v1/trips")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"routeId\":1,\"busId\":1,\"tripDate\":\"2030-10-01\",\"departureTime\":\"2030-10-01T08:00:00\"}"))
                .andExpect(status().isCreated());
    }

    // Verifica que se rechace un viaje sin ruta ni bus
    @Test
    @WithMockUser(roles = "ADMIN")
    void createTrip_shouldReturn400WhenInvalid() throws Exception {
        mvc.perform(post("/api/v1/trips")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tripDate\":\"2026-10-01\",\"departureTime\":\"2026-10-01T08:00:00\",\"arrivalEta\":\"2026-10-01T12:00:00\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.routeId").exists())
                .andExpect(jsonPath("$.validationErrors.busId").exists());

        verifyNoInteractions(tripService);
    }

    // Verifica que retorne 404 cuando la ruta o el bus no existen
    @Test
    @WithMockUser(roles = "ADMIN")
    void createTrip_shouldReturn404WhenRouteNotFound() throws Exception {
        when(tripService.createTrip(any())).thenThrow(new ResourceNotFoundException("Ruta", 1L));

        mvc.perform(post("/api/v1/trips")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(validCreateRequest())))
                .andExpect(status().isNotFound());
    }

    // Verifica que un DISPATCHER no pueda crear viajes
    @Test
    @WithMockUser(roles = "DISPATCHER")
    void createTrip_shouldReturn403ForDispatcher() throws Exception {
        mvc.perform(post("/api/v1/trips")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(validCreateRequest())))
                .andExpect(status().isForbidden());

        verifyNoInteractions(tripService);
    }

    // Verifica que sin autenticación no se puedan crear viajes
    @Test
    void createTrip_shouldReturn401WhenAnonymous() throws Exception {
        mvc.perform(post("/api/v1/trips")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(validCreateRequest())))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(tripService);
    }

    // PUT /trips/{id}/status

    // Verifica que un estado inexistente devuelva 400
    @Test
    @WithMockUser(roles = "ADMIN")
    void updateTripStatus_shouldReturn400WhenStatusUnknown() throws Exception {
        mvc.perform(put("/api/v1/trips/1/status")
                        .with(csrf())
                        .param("status", "VOLANDO"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(tripService);
    }

    // Verifica que falte el parámetro status
    @Test
    @WithMockUser(roles = "ADMIN")
    void updateTripStatus_shouldReturn400WhenStatusMissing() throws Exception {
        mvc.perform(put("/api/v1/trips/1/status")
                        .with(csrf()))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(tripService);
    }

    // Verifica que retorne 404 cuando el viaje no existe
    @Test
    @WithMockUser(roles = "ADMIN")
    void updateTripStatus_shouldReturn404WhenNotFound() throws Exception {
        when(tripService.updateTripStatus(99L, Trip.TripStatus.BOARDING)).thenThrow(new ResourceNotFoundException("Viaje", 99L));

        mvc.perform(put("/api/v1/trips/99/status")
                        .with(csrf())
                        .param("status", "BOARDING"))
                .andExpect(status().isNotFound());
    }

    // Verifica que una transición no permitida devuelva 422
    @Test
    @WithMockUser(roles = "ADMIN")
    void updateTripStatus_shouldReturn422WhenInvalidTransition() throws Exception {
        when(tripService.updateTripStatus(1L, Trip.TripStatus.SCHEDULED))
                .thenThrow(new InvalidStateTransitionException("ARRIVED", "SCHEDULED"));

        mvc.perform(put("/api/v1/trips/1/status")
                        .with(csrf())
                        .param("status", "SCHEDULED"))
                .andExpect(status().isUnprocessableEntity());
    }

    // Verifica que un DISPATCHER no pueda cambiar el estado de un viaje por esta vía
    @Test
    @WithMockUser(roles = "DISPATCHER")
    void updateTripStatus_shouldReturn403ForDispatcher() throws Exception {
        mvc.perform(put("/api/v1/trips/1/status")
                        .with(csrf())
                        .param("status", "BOARDING"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(tripService);
    }

    // DELETE /trips/{id}

    // Verifica que un PASSENGER no pueda cancelar viajes
    @Test
    @WithMockUser(roles = "PASSENGER")
    void cancelTrip_shouldReturn403ForPassenger() throws Exception {
        mvc.perform(delete("/api/v1/trips/1")
                        .with(csrf()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(tripService);
    }

    // Verifica que cancelar un viaje que ya partió devuelva 422
    @Test
    @WithMockUser(roles = "ADMIN")
    void cancelTrip_shouldReturn422WhenDeparted() throws Exception {
        doThrow(new InvalidStateTransitionException("DEPARTED", "CANCELLED"))
                .when(tripService).cancelTrip(1L);

        mvc.perform(delete("/api/v1/trips/1")
                        .with(csrf()))
                .andExpect(status().isUnprocessableEntity());
    }

    // Verifica que sin autenticación no se puedan cancelar viajes
    @Test
    void cancelTrip_shouldReturn401WhenAnonymous() throws Exception {
        mvc.perform(delete("/api/v1/trips/1"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(tripService);
    }
}

