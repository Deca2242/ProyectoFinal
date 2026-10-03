package com.web.controller;

import com.web.config.CustomUserDetailsService;
import com.web.config.SecurityConfig;
import com.web.dto.auth.User.UserResponse;
import com.web.dto.dispatch.Assignment.AssignmentResponse;
import com.web.entity.User;
import com.web.exception.ResourceNotFoundException;
import com.web.service.dispatch.AssignmentService;
import com.web.service.user.UserService;
import com.web.util.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AssignmentController.class)
@Import(SecurityConfig.class)
class AssignmentControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AssignmentService assignmentService;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private CustomUserDetailsService customUserDetailsService;

    private static final LocalDate DATE = LocalDate.of(2026, 12, 20);

    private AssignmentResponse assignment() {
        return new AssignmentResponse(1L, 10L, 3L, "Conductor", "300", 4L, "Despachador",
                true, true, true, LocalDateTime.now(), null, null, null, null, null, null, null, true, true);
    }

    private void givenCurrentUser(long id, User.Role role) {
        when(userService.getCurrentUser()).thenReturn(new UserResponse(id, "Usuario", "u@test.com", null,
                role, User.Status.ACTIVE, null));
    }

    // ---------- Conductor ----------

    // Verifica que el conductor vea sus asignaciones de una fecha
    @Test
    @WithMockUser(roles = "DRIVER")
    void getMyAssignments_shouldReturn200WithDate() throws Exception {
        givenCurrentUser(3L, User.Role.DRIVER);
        when(assignmentService.getDriverAssignments(3L, DATE)).thenReturn(List.of(assignment()));

        mvc.perform(get("/api/v1/assignments/me").param("date", DATE.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].tripId").value(10))
                .andExpect(jsonPath("$[0].driverId").value(3));
    }

    // Verifica que sin fecha se pidan todas sus asignaciones
    @Test
    @WithMockUser(roles = "DRIVER")
    void getMyAssignments_withoutDate_shouldReturn200() throws Exception {
        givenCurrentUser(3L, User.Role.DRIVER);
        when(assignmentService.getDriverAssignments(3L, null)).thenReturn(List.of());

        mvc.perform(get("/api/v1/assignments/me"))
                .andExpect(status().isOk());
        verify(assignmentService).getDriverAssignments(3L, null);
    }

    // Verifica 400 con fecha mal formada
    @Test
    @WithMockUser(roles = "DRIVER")
    void getMyAssignments_shouldReturn400WhenInvalidDate() throws Exception {
        mvc.perform(get("/api/v1/assignments/me").param("date", "20-12-2026"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(assignmentService);
    }

    // Verifica 404 si el usuario autenticado ya no existe
    @Test
    @WithMockUser(roles = "DRIVER")
    void getMyAssignments_shouldReturn404WhenUserNotFound() throws Exception {
        when(userService.getCurrentUser()).thenThrow(new ResourceNotFoundException("Usuario con email: user"));

        mvc.perform(get("/api/v1/assignments/me"))
                .andExpect(status().isNotFound());
    }

    // Verifica que un DISPATCHER no use el endpoint del conductor
    @Test
    @WithMockUser(roles = "DISPATCHER")
    void getMyAssignments_shouldReturn403ForDispatcher() throws Exception {
        mvc.perform(get("/api/v1/assignments/me"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(assignmentService, userService);
    }

    // Verifica 401 sin autenticación
    @Test
    void getMyAssignments_shouldReturn401WhenAnonymous() throws Exception {
        mvc.perform(get("/api/v1/assignments/me"))
                .andExpect(status().isUnauthorized());
    }

    // ---------- Despachador ----------

    // Verifica que el despachador vea las asignaciones que hizo en una fecha
    @Test
    @WithMockUser(roles = "DISPATCHER")
    void getDispatcherAssignments_shouldReturn200WithDate() throws Exception {
        givenCurrentUser(4L, User.Role.DISPATCHER);
        when(assignmentService.getDispatcherAssignments(4L, DATE)).thenReturn(List.of(assignment()));

        mvc.perform(get("/api/v1/assignments").param("date", DATE.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].dispatcherId").value(4));
    }

    // Verifica que sin fecha se pidan las de hoy en adelante
    @Test
    @WithMockUser(roles = "DISPATCHER")
    void getDispatcherAssignments_withoutDate_shouldReturn200() throws Exception {
        givenCurrentUser(4L, User.Role.DISPATCHER);
        when(assignmentService.getDispatcherAssignments(4L, null)).thenReturn(List.of());

        mvc.perform(get("/api/v1/assignments"))
                .andExpect(status().isOk());
        verify(assignmentService).getDispatcherAssignments(4L, null);
    }

    // Verifica que un DRIVER no vea las asignaciones del despachador
    @Test
    @WithMockUser(roles = "DRIVER")
    void getDispatcherAssignments_shouldReturn403ForDriver() throws Exception {
        mvc.perform(get("/api/v1/assignments"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(assignmentService);
    }

    // Verifica 401 sin autenticación
    @Test
    void getDispatcherAssignments_shouldReturn401WhenAnonymous() throws Exception {
        mvc.perform(get("/api/v1/assignments"))
                .andExpect(status().isUnauthorized());
    }
}
