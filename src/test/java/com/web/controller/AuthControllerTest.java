package com.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.dto.auth.Login.LoginRequest;
import com.web.dto.auth.Login.LoginResponse;
import com.web.dto.auth.Login.RegisterRequest;
import com.web.dto.auth.User.UserResponse;
import com.web.config.CustomUserDetailsService;
import com.web.config.SecurityConfig;
import com.web.entity.User;
import com.web.exception.EmailAlreadyExistsException;
import com.web.exception.InvalidCredentialsException;
import com.web.service.auth.AuthService;
import com.web.util.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;


// Se importa la SecurityConfig real para comprobar que /api/v1/auth/** es público
@WebMvcTest(AuthController.class)
@Import(SecurityConfig.class)
class AuthControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper om;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private CustomUserDetailsService customUserDetailsService;

    // Verifica que un usuario anónimo pueda registrarse correctamente
    @Test
    @WithAnonymousUser
    void register_shouldReturn201AndLocation() throws Exception {
        var req = new RegisterRequest("John Doe", "john@example.com", "123456789", "password123", null);
        var resp = new UserResponse(10L, "John Doe", "john@example.com", "123456789", User.Role.PASSENGER, User.Status.ACTIVE, null);

        when(authService.register(any())).thenReturn(resp);

        mvc.perform(post("/api/v1/auth/register")
                        .with(csrf())
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(10))
                .andExpect(jsonPath("$.email").value("john@example.com"));
    }

    // Verifica validación de datos en el registro
    @Test
    @WithAnonymousUser
    void register_shouldReturn400WhenInvalid() throws Exception{
        var req = new RegisterRequest("", "invalid-email", "", "pass", null);

        mvc.perform(post("/api/v1/auth/register")
                        .with(csrf())
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    // Verifica que un usuario pueda iniciar sesión y recibir un token JWT
    @Test
    @WithAnonymousUser
    void login_shouldReturn200WithToken() throws Exception {
        var req = new LoginRequest("john@example.com", "password123");
        var userResp = new UserResponse(1L, "John Doe", "john@example.com", "123456789", User.Role.PASSENGER, User.Status.ACTIVE, null);
        var resp = new LoginResponse("token123", "Bearer", userResp);

        when(authService.login(any())).thenReturn(resp);

        mvc.perform(post("/api/v1/auth/login")
                        .with(csrf())
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("token123"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.user.id").value(1));
    }

    // Verifica que credenciales inválidas retornen 401
    @Test
    @WithAnonymousUser
    void login_shouldReturn401WhenInvalidCredentials() throws Exception {
        var req = new LoginRequest("john@example.com", "wrongpassword");

        when(authService.login(any())).thenThrow(new InvalidCredentialsException("Credenciales inválidas"));

        mvc.perform(post("/api/v1/auth/login")
                        .with(csrf())
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------------
    // Casos adicionales: validación, seguridad y errores propagados
    // ---------------------------------------------------------------------

    // Verifica que el registro sea público: sin token ni usuario también responde 201
    @Test
    void register_shouldBePublicWithoutAuthentication() throws Exception {
        var req = new RegisterRequest("Ana", "ana@example.com", "3001234567", "password123", null);
        var resp = new UserResponse(11L, "Ana", "ana@example.com", "3001234567", User.Role.PASSENGER, User.Status.ACTIVE, null);

        when(authService.register(any())).thenReturn(resp);

        mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("PASSENGER"));

        verify(authService).register(eq(req));
    }

    // Verifica que el registro sin email devuelva 400 con el error en el campo email
    @Test
    void register_shouldReturn400WhenEmailMissing() throws Exception {
        var req = new RegisterRequest("Ana", null, "3001234567", "password123", null);

        mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Error de validación en los datos enviados"))
                .andExpect(jsonPath("$.validationErrors.email").exists());

        verifyNoInteractions(authService);
    }

    // Verifica que el registro con email vacío devuelva 400
    @Test
    void register_shouldReturn400WhenEmailBlank() throws Exception {
        var req = new RegisterRequest("Ana", "", "3001234567", "password123", null);

        mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.email").exists());

        verifyNoInteractions(authService);
    }

    // Verifica que el registro con email de formato inválido devuelva 400 (solo falla el email)
    @Test
    void register_shouldReturn400WhenEmailFormatInvalid() throws Exception {
        var req = new RegisterRequest("Ana", "ana-sin-arroba", "3001234567", "password123", null);

        mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.email").exists())
                .andExpect(jsonPath("$.validationErrors.name").doesNotExist())
                .andExpect(jsonPath("$.validationErrors.password").doesNotExist());

        verifyNoInteractions(authService);
    }

    // Verifica que el registro sin contraseña ni teléfono devuelva 400 con ambos campos
    @Test
    void register_shouldReturn400WhenPasswordAndPhoneMissing() throws Exception {
        var req = new RegisterRequest("Ana", "ana@example.com", null, null, null);

        mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.password").exists())
                .andExpect(jsonPath("$.validationErrors.phone").exists());
    }

    // Verifica que un JSON mal formado en el registro devuelva 400
    @Test
    void register_shouldReturn400WhenMalformedJson() throws Exception {
        mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"Ana\", \"email\": "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("El cuerpo de la petición no es válido o está mal formado"));

        verifyNoInteractions(authService);
    }

    // Verifica que el registro sin cuerpo devuelva 400
    @Test
    void register_shouldReturn400WhenBodyMissing() throws Exception {
        mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(authService);
    }

    // Verifica que un rol inexistente en el JSON devuelva 400
    @Test
    void register_shouldReturn400WhenRoleInvalid() throws Exception {
        mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Ana\",\"email\":\"ana@example.com\",\"phone\":\"300\",\"password\":\"x\",\"role\":\"SUPERADMIN\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(authService);
    }

    // Verifica que un email ya registrado devuelva 409
    @Test
    void register_shouldReturn409WhenEmailAlreadyExists() throws Exception {
        var req = new RegisterRequest("Ana", "ana@example.com", "3001234567", "password123", null);

        when(authService.register(any())).thenThrow(new EmailAlreadyExistsException("ana@example.com"));

        mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value("El email 'ana@example.com' ya está registrado"));
    }

    // Verifica que pedir un rol distinto de PASSENGER sin ser ADMIN devuelva 403 (no se degrada en silencio)
    @Test
    void register_shouldReturn403WhenRoleNotAllowed() throws Exception {
        var req = new RegisterRequest("Ana", "ana@example.com", "3001234567", "password123", User.Role.ADMIN);

        when(authService.register(any())).thenThrow(new com.web.exception.BusinessException(
                "Solo un administrador puede registrar usuarios con rol ADMIN",
                org.springframework.http.HttpStatus.FORBIDDEN, "ROLE_NOT_ALLOWED"));

        mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").value("Solo un administrador puede registrar usuarios con rol ADMIN"));
    }

    // Verifica que una contraseña de menos de 8 caracteres o un nombre de más de 100 sean 400 sin llamar al servicio
    @Test
    void register_shouldReturn400WhenPasswordTooShortOrNameTooLong() throws Exception {
        var req = new RegisterRequest("n".repeat(101), "ana@example.com", "3001234567", "corta12", null);

        mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.password").exists())
                .andExpect(jsonPath("$.validationErrors.name").exists());

        verifyNoInteractions(authService);
    }

    // Verifica que una violación de unicidad en BD (carrera entre registros) devuelva 409
    @Test
    void register_shouldReturn409WhenDataIntegrityViolation() throws Exception {
        var req = new RegisterRequest("Ana", "ana@example.com", "3001234567", "password123", null);

        when(authService.register(any())).thenThrow(new DataIntegrityViolationException("users_email_key"));

        mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isConflict());
    }

    // Verifica que el login sea público: sin token ni usuario también responde 200
    @Test
    void login_shouldBePublicWithoutAuthentication() throws Exception {
        var req = new LoginRequest("john@example.com", "password123");
        var userResp = new UserResponse(1L, "John Doe", "john@example.com", "123456789", User.Role.PASSENGER, User.Status.ACTIVE, null);

        when(authService.login(any())).thenReturn(new LoginResponse("token123", "Bearer", userResp));

        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("token123"));
    }

    // Verifica que el login sin email devuelva 400
    @Test
    void login_shouldReturn400WhenEmailMissing() throws Exception {
        var req = new LoginRequest(null, "password123");

        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.email").exists());

        verifyNoInteractions(authService);
    }

    // Verifica que el login con email vacío devuelva 400
    @Test
    void login_shouldReturn400WhenEmailBlank() throws Exception {
        var req = new LoginRequest("", "password123");

        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.email").exists());

        verifyNoInteractions(authService);
    }

    // Verifica que el login con email de formato inválido devuelva 400
    @Test
    void login_shouldReturn400WhenEmailFormatInvalid() throws Exception {
        var req = new LoginRequest("no-es-un-email", "password123");

        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.email").exists())
                .andExpect(jsonPath("$.validationErrors.password").doesNotExist());

        verifyNoInteractions(authService);
    }

    // Verifica que el login sin contraseña devuelva 400
    @Test
    void login_shouldReturn400WhenPasswordBlank() throws Exception {
        var req = new LoginRequest("john@example.com", "");

        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.password").exists());

        verifyNoInteractions(authService);
    }

    // Verifica que un JSON mal formado en el login devuelva 400
    @Test
    void login_shouldReturn400WhenMalformedJson() throws Exception {
        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("email=john@example.com&password=123"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(authService);
    }

    // Verifica que un usuario inactivo reciba 401 con el mensaje del servicio
    @Test
    void login_shouldReturn401WhenUserInactive() throws Exception {
        var req = new LoginRequest("john@example.com", "password123");

        when(authService.login(any())).thenThrow(new InvalidCredentialsException("Usuario inactivo"));

        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Usuario inactivo"));
    }
}

