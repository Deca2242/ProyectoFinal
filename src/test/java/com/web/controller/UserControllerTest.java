package com.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.config.CustomUserDetailsService;
import com.web.config.SecurityConfig;
import com.web.dto.auth.User.PasswordChangeRequest;
import com.web.dto.auth.User.UserResponse;
import com.web.entity.User;
import com.web.exception.BusinessException;
import com.web.exception.ResourceNotFoundException;
import com.web.service.user.UserService;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(UserController.class)
@Import(SecurityConfig.class)
class UserControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper om;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private CustomUserDetailsService customUserDetailsService;

    private UserResponse driver(User.Role role, User.Status status) {
        return new UserResponse(2L, "Driver", "driver@test.com", "300", role, status, LocalDateTime.now());
    }

    // ---------- Gestión de usuarios (ADMIN) ----------

    // Verifica que el ADMIN liste usuarios filtrados y sin el hash de la contraseña
    @Test
    @WithMockUser(roles = "ADMIN")
    void getUsers_shouldReturn200WithoutPasswordHash() throws Exception {
        when(userService.getUsers(User.Role.DRIVER, User.Status.ACTIVE))
                .thenReturn(List.of(driver(User.Role.DRIVER, User.Status.ACTIVE)));

        mvc.perform(get("/api/v1/admin/users").param("role", "DRIVER").param("status", "ACTIVE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].email").value("driver@test.com"))
                .andExpect(jsonPath("$[0].passwordHash").doesNotExist());
    }

    // Verifica que sin filtros se pidan todos los usuarios
    @Test
    @WithMockUser(roles = "ADMIN")
    void getUsers_withoutFilters_shouldReturn200() throws Exception {
        when(userService.getUsers(null, null)).thenReturn(List.of());

        mvc.perform(get("/api/v1/admin/users"))
                .andExpect(status().isOk());
        verify(userService).getUsers(null, null);
    }

    // Verifica 400 con un rol inexistente
    @Test
    @WithMockUser(roles = "ADMIN")
    void getUsers_shouldReturn400WhenInvalidRole() throws Exception {
        mvc.perform(get("/api/v1/admin/users").param("role", "SUPERUSER"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(userService);
    }

    // Verifica que un DISPATCHER no pueda listar usuarios
    @Test
    @WithMockUser(roles = "DISPATCHER")
    void getUsers_shouldReturn403ForDispatcher() throws Exception {
        mvc.perform(get("/api/v1/admin/users"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(userService);
    }

    // Verifica 401 sin autenticación
    @Test
    void getUsers_shouldReturn401WhenAnonymous() throws Exception {
        mvc.perform(get("/api/v1/admin/users"))
                .andExpect(status().isUnauthorized());
    }

    // Verifica que el ADMIN pueda desactivar un usuario
    @Test
    @WithMockUser(roles = "ADMIN")
    void updateStatus_shouldReturn200() throws Exception {
        when(userService.updateStatus(2L, User.Status.INACTIVE))
                .thenReturn(driver(User.Role.DRIVER, User.Status.INACTIVE));

        mvc.perform(patch("/api/v1/admin/users/2/status").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("status", "INACTIVE"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"));
    }

    // Verifica 400 cuando el ADMIN intenta desactivarse a sí mismo
    @Test
    @WithMockUser(roles = "ADMIN")
    void updateStatus_shouldReturn400WhenDeactivatingSelf() throws Exception {
        when(userService.updateStatus(1L, User.Status.INACTIVE)).thenThrow(new BusinessException(
                "Un administrador no puede desactivarse a sí mismo", HttpStatus.BAD_REQUEST, "CANNOT_DEACTIVATE_SELF"));

        mvc.perform(patch("/api/v1/admin/users/1/status").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("status", "INACTIVE"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Un administrador no puede desactivarse a sí mismo"));
    }

    // Verifica 400 con estado nulo o inválido
    @Test
    @WithMockUser(roles = "ADMIN")
    void updateStatus_shouldReturn400WhenInvalidStatus() throws Exception {
        mvc.perform(patch("/api/v1/admin/users/2/status").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(patch("/api/v1/admin/users/2/status").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"BLOCKED\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(userService);
    }

    // Verifica 404 cuando el usuario no existe
    @Test
    @WithMockUser(roles = "ADMIN")
    void updateStatus_shouldReturn404WhenUserNotFound() throws Exception {
        when(userService.updateStatus(99L, User.Status.INACTIVE)).thenThrow(new ResourceNotFoundException("Usuario", 99L));

        mvc.perform(patch("/api/v1/admin/users/99/status").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("status", "INACTIVE"))))
                .andExpect(status().isNotFound());
    }

    // Verifica que un CLERK no pueda cambiar estados
    @Test
    @WithMockUser(roles = "CLERK")
    void updateStatus_shouldReturn403ForClerk() throws Exception {
        mvc.perform(patch("/api/v1/admin/users/2/status").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("status", "INACTIVE"))))
                .andExpect(status().isForbidden());
    }

    // Verifica que el ADMIN pueda cambiar el rol de un usuario
    @Test
    @WithMockUser(roles = "ADMIN")
    void updateRole_shouldReturn200() throws Exception {
        when(userService.updateRole(2L, User.Role.DISPATCHER))
                .thenReturn(driver(User.Role.DISPATCHER, User.Status.ACTIVE));

        mvc.perform(patch("/api/v1/admin/users/2/role").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("role", "DISPATCHER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("DISPATCHER"));
    }

    // Verifica 400 sin rol
    @Test
    @WithMockUser(roles = "ADMIN")
    void updateRole_shouldReturn400WhenMissingRole() throws Exception {
        mvc.perform(patch("/api/v1/admin/users/2/role").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(userService);
    }

    // Verifica que un PASSENGER no pueda cambiar roles
    @Test
    @WithMockUser(roles = "PASSENGER")
    void updateRole_shouldReturn403ForPassenger() throws Exception {
        mvc.perform(patch("/api/v1/admin/users/2/role").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("role", "ADMIN"))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(userService);
    }

    // ---------- Perfil del usuario autenticado ----------

    // Verifica que cualquier autenticado consulte su perfil
    @Test
    @WithMockUser(roles = "PASSENGER")
    void getMe_shouldReturn200() throws Exception {
        when(userService.getCurrentUser()).thenReturn(driver(User.Role.PASSENGER, User.Status.ACTIVE));

        mvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(2))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    // Verifica 401 sin autenticación
    @Test
    void getMe_shouldReturn401WhenAnonymous() throws Exception {
        mvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(userService);
    }

    // Verifica el cambio de contraseña
    @Test
    @WithMockUser(roles = "DRIVER")
    void changePassword_shouldReturn204() throws Exception {
        var request = new PasswordChangeRequest("actual123", "nueva12345");

        mvc.perform(put("/api/v1/users/me/password").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(request)))
                .andExpect(status().isNoContent());
        verify(userService).changePassword(request);
    }

    // Verifica que la nueva contraseña tenga al menos 8 caracteres
    @Test
    @WithMockUser(roles = "DRIVER")
    void changePassword_shouldReturn400WhenNewPasswordTooShort() throws Exception {
        mvc.perform(put("/api/v1/users/me/password").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new PasswordChangeRequest("actual123", "corta"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.newPassword").exists());
        verifyNoInteractions(userService);
    }

    // Verifica 400 cuando la contraseña actual no coincide
    @Test
    @WithMockUser(roles = "DRIVER")
    void changePassword_shouldReturn400WhenCurrentPasswordWrong() throws Exception {
        doThrow(new BusinessException("La contraseña actual no es correcta", HttpStatus.BAD_REQUEST,
                "INVALID_CURRENT_PASSWORD")).when(userService).changePassword(any());

        mvc.perform(put("/api/v1/users/me/password").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new PasswordChangeRequest("mala12345", "nueva12345"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("La contraseña actual no es correcta"));
    }

    // Verifica 401 sin autenticación
    @Test
    void changePassword_shouldReturn401WhenAnonymous() throws Exception {
        mvc.perform(put("/api/v1/users/me/password").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new PasswordChangeRequest("actual123", "nueva12345"))))
                .andExpect(status().isUnauthorized());
    }
}
