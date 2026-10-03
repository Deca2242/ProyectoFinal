package com.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.dto.catalog.Bus.BusCreateRequest;
import com.web.dto.catalog.Bus.BusResponse;
import com.web.dto.catalog.Bus.BusUpdateRequest;
import com.web.dto.catalog.Seat.SeatResponse;
import com.web.dto.catalog.Seat.SeatUpdateRequest;
import com.web.entity.Bus;
import com.web.entity.Seat;
import com.web.config.CustomUserDetailsService;
import com.web.config.SecurityConfig;
import com.web.exception.BusinessException;
import com.web.exception.ResourceNotFoundException;
import com.web.service.catalog.BusService;
import com.web.util.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;


@WebMvcTest(BusController.class)
@Import(SecurityConfig.class)
class BusControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper om;

    @MockitoBean
    private BusService busService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private CustomUserDetailsService customUserDetailsService;

    // Verifica que un ADMIN pueda crear un bus
    @Test
    @WithMockUser(roles = "ADMIN")
    void createBus_shouldReturn201() throws Exception {
        var req = new BusCreateRequest("ABC123", 40, null);
        var resp = new BusResponse(1L, "ABC123", 40, null, Bus.BusStatus.ACTIVE);

        when(busService.createBus(any())).thenReturn(resp);

        mvc.perform(post("/api/v1/buses").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.plate").value("ABC123"));
    }

    // Verifica que un DISPATCHER pueda consultar todos los buses
    @Test
    @WithMockUser(roles = "DISPATCHER")
    void getAllBuses_shouldReturn200() throws Exception {
        var resp = List.of(new BusResponse(1L, "ABC123", 40, null, Bus.BusStatus.ACTIVE));

        when(busService.getAllBuses()).thenReturn(resp);

        mvc.perform(get("/api/v1/buses"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1));
    }

    // Verifica que un DISPATCHER pueda consultar un bus por ID
    @Test
    @WithMockUser(roles = "DISPATCHER")
    void getBusById_shouldReturn200() throws Exception {
        var resp = new BusResponse(1L, "ABC123", 40, null, Bus.BusStatus.ACTIVE);

        when(busService.getBusById(1L)).thenReturn(resp);

        mvc.perform(get("/api/v1/buses/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1));
    }

    // Verifica que retorne 404 cuando el bus no existe
    @Test
    @WithMockUser(roles = "DISPATCHER")
    void getBusById_shouldReturn404WhenNotFound() throws Exception {
        when(busService.getBusById(99L)).thenThrow(new ResourceNotFoundException("Bus", 99L));

        mvc.perform(get("/api/v1/buses/99"))
                .andExpect(status().isNotFound());
    }

    // Verifica que un DISPATCHER pueda buscar un bus por placa
    @Test
    @WithMockUser(roles = "DISPATCHER")
    void getBusByPlate_shouldReturn200() throws Exception {
        var resp = new BusResponse(1L, "ABC123", 40, null, Bus.BusStatus.ACTIVE);

        when(busService.getBusByPlate("ABC123")).thenReturn(resp);

        mvc.perform(get("/api/v1/buses/plate/ABC123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plate").value("ABC123"));
    }

    // Verifica que un DISPATCHER pueda consultar buses disponibles por fecha
    @Test
    @WithMockUser(roles = "DISPATCHER")
    void getAvailableBuses_shouldReturn200() throws Exception {
        var resp = List.of(new BusResponse(1L, "ABC123", 40, null, Bus.BusStatus.ACTIVE));

        when(busService.getAvailableBuses(LocalDate.now(), null, null)).thenReturn(resp);

        mvc.perform(get("/api/v1/buses/available")
                        .param("date", LocalDate.now().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1));
    }

    // Verifica que se puedan consultar buses disponibles por franja horaria
    @Test
    @WithMockUser(roles = "ADMIN")
    void getAvailableBuses_withTimeSlot_shouldPassItToService() throws Exception {
        LocalDateTime departure = LocalDateTime.of(2030, 1, 15, 14, 0);
        LocalDateTime arrival = LocalDateTime.of(2030, 1, 15, 18, 0);
        when(busService.getAvailableBuses(null, departure, arrival)).thenReturn(List.of());

        mvc.perform(get("/api/v1/buses/available")
                        .param("departureTime", "2030-01-15T14:00:00")
                        .param("arrivalEta", "2030-01-15T18:00:00"))
                .andExpect(status().isOk());

        verify(busService).getAvailableBuses(null, departure, arrival);
    }

    // GET/PUT /buses/{id}/seats

    // Verifica que un DISPATCHER pueda consultar las sillas de un bus
    @Test
    @WithMockUser(roles = "DISPATCHER")
    void getSeats_shouldReturn200() throws Exception {
        when(busService.getSeats(1L)).thenReturn(List.of(
                new SeatResponse(1L, 1L, 1, Seat.SeatType.STANDARD),
                new SeatResponse(2L, 1L, 2, Seat.SeatType.PREFERENTIAL)));

        mvc.perform(get("/api/v1/buses/1/seats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].seatType").value("PREFERENTIAL"));
    }

    // Verifica que un ADMIN pueda marcar una silla como preferencial
    @Test
    @WithMockUser(roles = "ADMIN")
    void updateSeat_shouldReturn200() throws Exception {
        var req = new SeatUpdateRequest(Seat.SeatType.PREFERENTIAL);
        when(busService.updateSeat(1L, 3, req)).thenReturn(new SeatResponse(3L, 1L, 3, Seat.SeatType.PREFERENTIAL));

        mvc.perform(put("/api/v1/buses/1/seats/3").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seatNumber").value(3))
                .andExpect(jsonPath("$.seatType").value("PREFERENTIAL"));
    }

    // Verifica que el tipo de silla sea obligatorio
    @Test
    @WithMockUser(roles = "ADMIN")
    void updateSeat_shouldReturn400WhenSeatTypeMissing() throws Exception {
        mvc.perform(put("/api/v1/buses/1/seats/3").contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.seatType").exists());

        verifyNoInteractions(busService);
    }

    // Verifica que un DISPATCHER no pueda cambiar el tipo de una silla
    @Test
    @WithMockUser(roles = "DISPATCHER")
    void updateSeat_shouldReturn403WhenNotAdmin() throws Exception {
        mvc.perform(put("/api/v1/buses/1/seats/3").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new SeatUpdateRequest(Seat.SeatType.PREFERENTIAL))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(busService);
    }

    // Verifica que una silla inexistente devuelva 404
    @Test
    @WithMockUser(roles = "ADMIN")
    void updateSeat_shouldReturn404WhenSeatNotFound() throws Exception {
        var req = new SeatUpdateRequest(Seat.SeatType.PREFERENTIAL);
        when(busService.updateSeat(1L, 99, req)).thenThrow(new ResourceNotFoundException("Silla 99 del bus 1 no encontrada"));

        mvc.perform(put("/api/v1/buses/1/seats/99").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isNotFound());
    }

    // Verifica que un ADMIN pueda actualizar los datos de un bus
    @Test
    @WithMockUser(roles = "ADMIN")
    void updateBus_shouldReturn200() throws Exception {
        var req = new BusUpdateRequest(45, null, Bus.BusStatus.ACTIVE);
        var resp = new BusResponse(1L, "ABC123", 45, null, Bus.BusStatus.ACTIVE);

        when(busService.updateBus(1L, req)).thenReturn(resp);

        mvc.perform(put("/api/v1/buses/1").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capacity").value(45));
    }

    // Verifica que un ADMIN pueda eliminar un bus
    @Test
    @WithMockUser(roles = "ADMIN")
    void deleteBus_shouldReturn204() throws Exception {
        mvc.perform(delete("/api/v1/buses/1")
                        .with(csrf()))
                .andExpect(status().isNoContent());
    }

    // ---------------------------------------------------------------------
    // Casos adicionales: validación, seguridad y errores propagados
    // ---------------------------------------------------------------------

    // Verifica que crear un bus sin placa ni capacidad devuelva 400 con los errores por campo
    @Test
    @WithMockUser(roles = "ADMIN")
    void createBus_shouldReturn400WhenInvalid() throws Exception {
        var req = new BusCreateRequest("", null, null);

        mvc.perform(post("/api/v1/buses").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.validationErrors.plate").exists())
                .andExpect(jsonPath("$.validationErrors.capacity").exists());

        verifyNoInteractions(busService);
    }

    // Verifica que un JSON mal formado devuelva 400 y no llegue al servicio
    @Test
    @WithMockUser(roles = "ADMIN")
    void createBus_shouldReturn400WhenMalformedJson() throws Exception {
        mvc.perform(post("/api/v1/buses").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"plate\": \"ABC123\", \"capacity\": "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("El cuerpo de la petición no es válido o está mal formado"));

        verifyNoInteractions(busService);
    }

    // Verifica que una capacidad con tipo incorrecto en el JSON devuelva 400
    @Test
    @WithMockUser(roles = "ADMIN")
    void createBus_shouldReturn400WhenCapacityIsNotNumeric() throws Exception {
        mvc.perform(post("/api/v1/buses").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"plate\": \"ABC123\", \"capacity\": \"cuarenta\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(busService);
    }

    // Verifica que crear un bus sin autenticación devuelva 401
    @Test
    void createBus_shouldReturn401WhenNotAuthenticated() throws Exception {
        var req = new BusCreateRequest("ABC123", 40, null);

        mvc.perform(post("/api/v1/buses").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("No autenticado"));

        verifyNoInteractions(busService);
    }

    // Verifica que un DISPATCHER no pueda crear buses (403)
    @Test
    @WithMockUser(roles = "DISPATCHER")
    void createBus_shouldReturn403WhenNotAdmin() throws Exception {
        var req = new BusCreateRequest("ABC123", 40, null);

        mvc.perform(post("/api/v1/buses").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Acceso denegado"));

        verifyNoInteractions(busService);
    }

    // Verifica que una placa duplicada detectada por el servicio devuelva 409
    @Test
    @WithMockUser(roles = "ADMIN")
    void createBus_shouldReturn409WhenPlateExists() throws Exception {
        var req = new BusCreateRequest("ABC123", 40, null);

        when(busService.createBus(any())).thenThrow(
                new BusinessException("Ya existe un bus con la placa: ABC123", HttpStatus.CONFLICT, "PLATE_EXISTS"));

        mvc.perform(post("/api/v1/buses").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Ya existe un bus con la placa: ABC123"));
    }

    // Verifica que una violación de integridad en la BD devuelva 409 sin exponer el detalle SQL
    @Test
    @WithMockUser(roles = "ADMIN")
    void createBus_shouldReturn409WhenDataIntegrityViolation() throws Exception {
        var req = new BusCreateRequest("ABC123", 40, null);

        when(busService.createBus(any())).thenThrow(
                new DataIntegrityViolationException("duplicate key value violates unique constraint \"buses_plate_key\""));

        mvc.perform(post("/api/v1/buses").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", not(containsString("buses_plate_key"))));
    }

    // Verifica que un ADMIN también pueda consultar todos los buses
    @Test
    @WithMockUser(roles = "ADMIN")
    void getAllBuses_shouldReturn200ForAdmin() throws Exception {
        when(busService.getAllBuses()).thenReturn(List.of());

        mvc.perform(get("/api/v1/buses"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    // Verifica que un PASSENGER no pueda consultar los buses (403)
    @Test
    @WithMockUser(roles = "PASSENGER")
    void getAllBuses_shouldReturn403WhenPassenger() throws Exception {
        mvc.perform(get("/api/v1/buses"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(busService);
    }

    // Verifica que consultar los buses sin autenticación devuelva 401
    @Test
    void getAllBuses_shouldReturn401WhenNotAuthenticated() throws Exception {
        mvc.perform(get("/api/v1/buses"))
                .andExpect(status().isUnauthorized());
    }

    // Verifica que un token JWT válido de un ADMIN permita el acceso a través del filtro real
    @Test
    void getAllBuses_shouldReturn200WithValidJwtToken() throws Exception {
        when(jwtTokenProvider.validateToken("token-valido")).thenReturn(true);
        when(jwtTokenProvider.extractEmail("token-valido")).thenReturn("admin@example.com");
        when(customUserDetailsService.loadUserByUsername("admin@example.com")).thenReturn(
                User.withUsername("admin@example.com").password("x").roles("ADMIN").build());
        when(busService.getAllBuses()).thenReturn(List.of());

        mvc.perform(get("/api/v1/buses").header("Authorization", "Bearer token-valido"))
                .andExpect(status().isOk());
    }

    // Verifica que un token JWT inválido no autentique la petición (401)
    @Test
    void getAllBuses_shouldReturn401WithInvalidJwtToken() throws Exception {
        when(jwtTokenProvider.validateToken("token-invalido")).thenReturn(false);

        mvc.perform(get("/api/v1/buses").header("Authorization", "Bearer token-invalido"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(busService);
    }

    // Verifica que un usuario desactivado con token aún vigente no quede autenticado (401)
    @Test
    void getAllBuses_shouldReturn401WhenUserDisabled() throws Exception {
        when(jwtTokenProvider.validateToken("token-valido")).thenReturn(true);
        when(jwtTokenProvider.extractEmail("token-valido")).thenReturn("admin@example.com");
        when(customUserDetailsService.loadUserByUsername("admin@example.com")).thenReturn(
                User.withUsername("admin@example.com").password("x").roles("ADMIN").disabled(true).build());

        mvc.perform(get("/api/v1/buses").header("Authorization", "Bearer token-valido"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(busService);
    }

    // Verifica que un id no numérico en la URL devuelva 400
    @Test
    @WithMockUser(roles = "DISPATCHER")
    void getBusById_shouldReturn400WhenIdNotNumeric() throws Exception {
        mvc.perform(get("/api/v1/buses/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Parámetro inválido o faltante en la petición"));

        verifyNoInteractions(busService);
    }

    // Verifica que un CLERK no pueda consultar un bus por ID (403)
    @Test
    @WithMockUser(roles = "CLERK")
    void getBusById_shouldReturn403WhenClerk() throws Exception {
        mvc.perform(get("/api/v1/buses/1"))
                .andExpect(status().isForbidden());
    }

    // Verifica que retorne 404 cuando no existe un bus con la placa indicada
    @Test
    @WithMockUser(roles = "ADMIN")
    void getBusByPlate_shouldReturn404WhenNotFound() throws Exception {
        when(busService.getBusByPlate("XYZ999")).thenThrow(new ResourceNotFoundException("Bus", "XYZ999"));

        mvc.perform(get("/api/v1/buses/plate/XYZ999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Bus con identificador 'XYZ999' no encontrado"));
    }

    // Verifica que consultar un bus por placa sin autenticación devuelva 401
    @Test
    void getBusByPlate_shouldReturn401WhenNotAuthenticated() throws Exception {
        mvc.perform(get("/api/v1/buses/plate/ABC123"))
                .andExpect(status().isUnauthorized());
    }

    // Verifica que sin fecha ni franja horaria devuelva 400
    @Test
    @WithMockUser(roles = "DISPATCHER")
    void getAvailableBuses_shouldReturn400WhenDateMissing() throws Exception {
        mvc.perform(get("/api/v1/buses/available"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Debe proporcionar la fecha o la franja horaria (departureTime y arrivalEta)"));

        verifyNoInteractions(busService);
    }

    // Verifica que una fecha con formato inválido devuelva 400
    @Test
    @WithMockUser(roles = "DISPATCHER")
    void getAvailableBuses_shouldReturn400WhenDateInvalid() throws Exception {
        mvc.perform(get("/api/v1/buses/available").param("date", "26-09-2026"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(busService);
    }

    // Verifica que un DRIVER no pueda consultar buses disponibles (403)
    @Test
    @WithMockUser(roles = "DRIVER")
    void getAvailableBuses_shouldReturn403WhenDriver() throws Exception {
        mvc.perform(get("/api/v1/buses/available").param("date", LocalDate.now().toString()))
                .andExpect(status().isForbidden());
    }

    // Verifica que actualizar un bus inexistente devuelva 404
    @Test
    @WithMockUser(roles = "ADMIN")
    void updateBus_shouldReturn404WhenNotFound() throws Exception {
        var req = new BusUpdateRequest(45, null, Bus.BusStatus.ACTIVE);

        when(busService.updateBus(99L, req)).thenThrow(new ResourceNotFoundException("Bus", 99L));

        mvc.perform(put("/api/v1/buses/99").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isNotFound());
    }

    // Verifica que un estado de bus inexistente en el JSON devuelva 400
    @Test
    @WithMockUser(roles = "ADMIN")
    void updateBus_shouldReturn400WhenStatusInvalid() throws Exception {
        mvc.perform(put("/api/v1/buses/1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"capacity\": 45, \"status\": \"VOLANDO\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(busService);
    }

    // Verifica que un DISPATCHER no pueda actualizar buses (403)
    @Test
    @WithMockUser(roles = "DISPATCHER")
    void updateBus_shouldReturn403WhenNotAdmin() throws Exception {
        var req = new BusUpdateRequest(45, null, Bus.BusStatus.ACTIVE);

        mvc.perform(put("/api/v1/buses/1").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(busService);
    }

    // Verifica que actualizar un bus sin autenticación devuelva 401
    @Test
    void updateBus_shouldReturn401WhenNotAuthenticated() throws Exception {
        var req = new BusUpdateRequest(45, null, Bus.BusStatus.ACTIVE);

        mvc.perform(put("/api/v1/buses/1").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isUnauthorized());
    }

    // Verifica que eliminar un bus invoque al servicio con el id de la URL
    @Test
    @WithMockUser(roles = "ADMIN")
    void deleteBus_shouldCallService() throws Exception {
        mvc.perform(delete("/api/v1/buses/7"))
                .andExpect(status().isNoContent());

        verify(busService).deleteBus(7L);
    }

    // Verifica que eliminar un bus inexistente devuelva 404
    @Test
    @WithMockUser(roles = "ADMIN")
    void deleteBus_shouldReturn404WhenNotFound() throws Exception {
        doThrow(new ResourceNotFoundException("Bus", 99L)).when(busService).deleteBus(99L);

        mvc.perform(delete("/api/v1/buses/99"))
                .andExpect(status().isNotFound());
    }

    // Verifica que retirar un bus con viajes programados devuelva 409
    @Test
    @WithMockUser(roles = "ADMIN")
    void deleteBus_shouldReturn409WhenReferenced() throws Exception {
        doThrow(new BusinessException("El bus tiene viajes programados: reasígnelos antes de retirarlo",
                HttpStatus.CONFLICT, "BUS_HAS_TRIPS")).when(busService).deleteBus(1L);

        mvc.perform(delete("/api/v1/buses/1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("El bus tiene viajes programados: reasígnelos antes de retirarlo"));
    }

    // Verifica que bajar la capacidad por debajo de una silla vendida devuelva 409
    @Test
    @WithMockUser(roles = "ADMIN")
    void updateBus_shouldReturn409WhenCapacityBelowSoldSeats() throws Exception {
        var req = new BusUpdateRequest(20, null, null);
        when(busService.updateBus(1L, req)).thenThrow(new BusinessException(
                "Hay tiquetes vendidos hasta la silla 30 en viajes futuros: la capacidad no puede ser menor",
                HttpStatus.CONFLICT, "CAPACITY_BELOW_SOLD_SEATS"));

        mvc.perform(put("/api/v1/buses/1").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isConflict());
    }

    // Verifica que un DISPATCHER no pueda eliminar buses (403)
    @Test
    @WithMockUser(roles = "DISPATCHER")
    void deleteBus_shouldReturn403WhenNotAdmin() throws Exception {
        mvc.perform(delete("/api/v1/buses/1"))
                .andExpect(status().isForbidden());

        verify(busService, never()).deleteBus(any());
    }

    // Verifica que eliminar un bus sin autenticación devuelva 401
    @Test
    void deleteBus_shouldReturn401WhenNotAuthenticated() throws Exception {
        mvc.perform(delete("/api/v1/buses/1"))
                .andExpect(status().isUnauthorized());
    }

    // Verifica que un error inesperado devuelva 500 sin exponer el mensaje interno
    @Test
    @WithMockUser(roles = "ADMIN")
    void getAllBuses_shouldReturn500WithoutInternalMessage() throws Exception {
        when(busService.getAllBuses()).thenThrow(new RuntimeException("ERROR: relation \"buses\" does not exist"));

        mvc.perform(get("/api/v1/buses"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("Error interno del servidor"))
                .andExpect(content().string(not(containsString("relation"))));
    }
}
