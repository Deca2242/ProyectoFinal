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

    // Respuesta de asignación reutilizada: id de asignación (5) distinto del id del viaje (1)
    private AssignmentResponse assignment(boolean checklistOk, Long driverId) {
        return new AssignmentResponse(
                5L, 1L, driverId, "Driver Name", "123456789",
                2L, "Dispatcher Name",
                checklistOk, checklistOk, checklistOk,
                LocalDateTime.now()
        );
    }

    // POST /assign

    // Verifica que el body sin driverId sea rechazado por @Valid
    @Test
    @WithMockUser(roles = "DISPATCHER")
    void assignTrip_shouldReturn400WhenDriverIdMissing() throws Exception {
        mvc.perform(post("/api/v1/trips/1/assign").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tripId\":1,\"dispatcherId\":2}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.driverId").exists());

        verifyNoInteractions(assignmentService);
    }

    // Verifica que el tripId de la URL se use para la asignación
    @Test
    @WithMockUser(roles = "DISPATCHER")
    void assignTrip_shouldUseTripIdFromUrl() throws Exception {
        var req = new AssignmentCreateRequest(7L, 1L, 2L);
        when(assignmentService.assignTrip(any())).thenReturn(assignment(false, 1L));

        mvc.perform(post("/api/v1/trips/7/assign").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isCreated());

        ArgumentCaptor<AssignmentCreateRequest> captor = ArgumentCaptor.forClass(AssignmentCreateRequest.class);
        verify(assignmentService).assignTrip(captor.capture());
        assertThat(captor.getValue().tripId()).isEqualTo(7L);
        assertThat(captor.getValue().driverId()).isEqualTo(1L);
        assertThat(captor.getValue().dispatcherId()).isEqualTo(2L);
    }

    // Verifica que retorne 404 cuando el viaje a asignar no existe
    @Test
    @WithMockUser(roles = "DISPATCHER")
    void assignTrip_shouldReturn404WhenTripNotFound() throws Exception {
        var req = new AssignmentCreateRequest(99L, 1L, 2L);
        when(assignmentService.assignTrip(any())).thenThrow(new ResourceNotFoundException("Viaje", 99L));

        mvc.perform(post("/api/v1/trips/99/assign").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Viaje con id 99 no encontrado"));
    }

    // Verifica que el conflicto de asignación duplicada se propague como 409
    @Test
    @WithMockUser(roles = "DISPATCHER")
    void assignTrip_shouldReturn409WhenAssignmentAlreadyExists() throws Exception {
        var req = new AssignmentCreateRequest(1L, 1L, 2L);
        when(assignmentService.assignTrip(any())).thenThrow(
                new BusinessException("Este viaje ya tiene una asignación", HttpStatus.CONFLICT, "ASSIGNMENT_EXISTS"));

        mvc.perform(post("/api/v1/trips/1/assign").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isConflict());
    }

    // Verifica que un DRIVER no pueda asignar viajes
    @Test
    @WithMockUser(roles = "DRIVER")
    void assignTrip_shouldReturn403ForDriver() throws Exception {
        var req = new AssignmentCreateRequest(1L, 1L, 2L);

        mvc.perform(post("/api/v1/trips/1/assign").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(assignmentService);
    }

    // Verifica que sin autenticación se responda 401
    @Test
    void assignTrip_shouldReturn401WhenAnonymous() throws Exception {
        var req = new AssignmentCreateRequest(1L, 1L, 2L);

        mvc.perform(post("/api/v1/trips/1/assign").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(assignmentService);
    }

    // GET /assignment

    // Verifica que un DISPATCHER pueda consultar la asignación de un viaje
    @Test
    @WithMockUser(roles = "DISPATCHER")
    void getAssignment_shouldReturn200ForDispatcher() throws Exception {
        when(assignmentService.getAssignmentByTrip(1L)).thenReturn(assignment(true, 1L));

        mvc.perform(get("/api/v1/trips/1/assignment"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(5))
                .andExpect(jsonPath("$.tripId").value(1))
                .andExpect(jsonPath("$.checklistOk").value(true));
    }

    // Verifica que un DRIVER también pueda consultar la asignación
    @Test
    @WithMockUser(roles = "DRIVER")
    void getAssignment_shouldReturn200ForDriver() throws Exception {
        when(assignmentService.getAssignmentByTrip(1L)).thenReturn(assignment(false, 1L));

        mvc.perform(get("/api/v1/trips/1/assignment"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.driverName").value("Driver Name"));
    }

    // Verifica que retorne 404 cuando el viaje no tiene asignación
    @Test
    @WithMockUser(roles = "DISPATCHER")
    void getAssignment_shouldReturn404WhenNoAssignment() throws Exception {
        when(assignmentService.getAssignmentByTrip(99L))
                .thenThrow(new ResourceNotFoundException("Asignación para viaje: 99"));

        mvc.perform(get("/api/v1/trips/99/assignment"))
                .andExpect(status().isNotFound());
    }

    // Verifica que un PASSENGER no pueda consultar asignaciones
    @Test
    @WithMockUser(roles = "PASSENGER")
    void getAssignment_shouldReturn403ForPassenger() throws Exception {
        mvc.perform(get("/api/v1/trips/1/assignment"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(assignmentService);
    }

    // Verifica que un CLERK no pueda consultar asignaciones
    @Test
    @WithMockUser(roles = "CLERK")
    void getAssignment_shouldReturn403ForClerk() throws Exception {
        mvc.perform(get("/api/v1/trips/1/assignment"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(assignmentService);
    }

    // Verifica que sin autenticación se responda 401
    @Test
    void getAssignment_shouldReturn401WhenAnonymous() throws Exception {
        mvc.perform(get("/api/v1/trips/1/assignment"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(assignmentService);
    }

    // Verifica que un tripId no numérico devuelva 400
    @Test
    @WithMockUser(roles = "DISPATCHER")
    void getAssignment_shouldReturn400WhenTripIdIsNotNumeric() throws Exception {
        mvc.perform(get("/api/v1/trips/abc/assignment"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(assignmentService);
    }

    // PUT /assignment

    // Verifica que un DISPATCHER actualice el checklist usando el id de la asignación (no el del viaje)
    @Test
    @WithMockUser(roles = "DISPATCHER")
    void updateAssignment_shouldReturn200AndUseAssignmentId() throws Exception {
        var req = new AssignmentUpdateRequest(null, true, true, true);
        when(assignmentService.getAssignmentByTrip(1L)).thenReturn(assignment(false, 1L));
        when(assignmentService.updateChecklist(eq(5L), any(AssignmentUpdateRequest.class)))
                .thenReturn(assignment(true, 1L));

        mvc.perform(put("/api/v1/trips/1/assignment").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checklistOk").value(true))
                .andExpect(jsonPath("$.soatValid").value(true))
                .andExpect(jsonPath("$.revisionValid").value(true));

        ArgumentCaptor<AssignmentUpdateRequest> captor = ArgumentCaptor.forClass(AssignmentUpdateRequest.class);
        verify(assignmentService).updateChecklist(eq(5L), captor.capture());
        assertThat(captor.getValue().checklistOk()).isTrue();
        assertThat(captor.getValue().driverId()).isNull();
    }

    // Verifica que se pueda cambiar el conductor asignado
    @Test
    @WithMockUser(roles = "DISPATCHER")
    void updateAssignment_shouldChangeDriver() throws Exception {
        var req = new AssignmentUpdateRequest(8L, null, null, null);
        when(assignmentService.getAssignmentByTrip(1L)).thenReturn(assignment(false, 1L));
        when(assignmentService.updateChecklist(eq(5L), any(AssignmentUpdateRequest.class)))
                .thenReturn(assignment(false, 8L));

        mvc.perform(put("/api/v1/trips/1/assignment").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.driverId").value(8));
    }

    // Verifica que el nuevo conductor sin rol DRIVER se rechace con 400
    @Test
    @WithMockUser(roles = "DISPATCHER")
    void updateAssignment_shouldReturn400WhenUserIsNotDriver() throws Exception {
        var req = new AssignmentUpdateRequest(9L, null, null, null);
        when(assignmentService.getAssignmentByTrip(1L)).thenReturn(assignment(false, 1L));
        when(assignmentService.updateChecklist(eq(5L), any(AssignmentUpdateRequest.class)))
                .thenThrow(new BusinessException("El usuario no es un conductor", HttpStatus.BAD_REQUEST, "INVALID_DRIVER_ROLE"));

        mvc.perform(put("/api/v1/trips/1/assignment").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("El usuario no es un conductor"));
    }

    // Verifica que retorne 404 cuando el viaje no tiene asignación y no se intente actualizar
    @Test
    @WithMockUser(roles = "DISPATCHER")
    void updateAssignment_shouldReturn404WhenNoAssignment() throws Exception {
        var req = new AssignmentUpdateRequest(null, true, true, true);
        when(assignmentService.getAssignmentByTrip(99L))
                .thenThrow(new ResourceNotFoundException("Asignación para viaje: 99"));

        mvc.perform(put("/api/v1/trips/99/assignment").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isNotFound());

        verify(assignmentService, never()).updateChecklist(any(), any());
    }

    // Verifica que un body mal formado devuelva 400
    @Test
    @WithMockUser(roles = "DISPATCHER")
    void updateAssignment_shouldReturn400WhenBodyIsMalformed() throws Exception {
        mvc.perform(put("/api/v1/trips/1/assignment").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"checklistOk\": \"quizas\""))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(assignmentService);
    }

    // Verifica que un DRIVER no pueda modificar la asignación (solo consultarla)
    @Test
    @WithMockUser(roles = "DRIVER")
    void updateAssignment_shouldReturn403ForDriver() throws Exception {
        var req = new AssignmentUpdateRequest(null, true, true, true);

        mvc.perform(put("/api/v1/trips/1/assignment").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(assignmentService);
    }

    // Verifica que un ADMIN tampoco pueda modificar la asignación
    @Test
    @WithMockUser(roles = "ADMIN")
    void updateAssignment_shouldReturn403ForAdmin() throws Exception {
        var req = new AssignmentUpdateRequest(null, true, true, true);

        mvc.perform(put("/api/v1/trips/1/assignment").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(assignmentService);
    }

    // Boarding y depart: errores y roles

    // Verifica que abrir abordaje desde un estado inválido devuelva 422
    @Test
    @WithMockUser(roles = "DISPATCHER")
    void controlBoarding_shouldReturn422WhenInvalidTransition() throws Exception {
        when(boardingService.openBoarding(1L)).thenThrow(
                new InvalidStateTransitionException("Solo se puede abrir abordaje desde estado SCHEDULED (actual: DEPARTED)"));

        mvc.perform(post("/api/v1/trips/1/boarding/open").with(csrf()))
                .andExpect(status().isUnprocessableEntity());
    }

    // Verifica que retorne 404 cuando el viaje no existe
    @Test
    @WithMockUser(roles = "DISPATCHER")
    void controlBoarding_shouldReturn404WhenTripNotFound() throws Exception {
        when(boardingService.closeBoarding(99L)).thenThrow(new ResourceNotFoundException("Viaje", 99L));

        mvc.perform(post("/api/v1/trips/99/boarding/close").with(csrf()))
                .andExpect(status().isNotFound());
    }

    // Verifica que un DRIVER no pueda abrir el abordaje
    @Test
    @WithMockUser(roles = "DRIVER")
    void controlBoarding_shouldReturn403ForDriver() throws Exception {
        mvc.perform(post("/api/v1/trips/1/boarding/open").with(csrf()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(boardingService);
    }

    // Verifica que un DISPATCHER no pueda marcar la salida (solo el conductor)
    @Test
    @WithMockUser(roles = "DISPATCHER")
    void departTrip_shouldReturn403ForDispatcher() throws Exception {
        mvc.perform(post("/api/v1/trips/1/depart").with(csrf()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(boardingService);
    }

    // Verifica que partir sin checklist aprobado devuelva 400
    @Test
    @WithMockUser(roles = "DRIVER")
    void departTrip_shouldReturn400WhenChecklistNotApproved() throws Exception {
        when(boardingService.departTrip(1L)).thenThrow(new BusinessException(
                "No se puede partir sin checklist aprobado", HttpStatus.BAD_REQUEST, "CHECKLIST_NOT_APPROVED"));

        mvc.perform(post("/api/v1/trips/1/depart").with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("No se puede partir sin checklist aprobado"));
    }
}

