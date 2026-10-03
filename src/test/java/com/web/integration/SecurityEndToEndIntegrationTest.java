package com.web.integration;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.dto.auth.Login.LoginRequest;
import com.web.dto.auth.Login.RegisterRequest;
import com.web.dto.auth.User.PasswordChangeRequest;
import com.web.entity.User;
import com.web.integration.userstories.BaseIntegrationTest;
import com.web.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Seguridad de extremo a extremo (HTTP + JWT real + BD): forma uniforme de 401/403, tokens inválidos,
// expirados o revocados, matriz de roles por endpoint y registro con rol
class SecurityEndToEndIntegrationTest extends BaseIntegrationTest {

    private static final String PASSWORD = "secreto123";
    private static final String FORBIDDEN_MESSAGE = "No tienes los permisos suficientes para realizar esta acción.";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper om;

    @Autowired
    private UserRepository userRepository;

    @Value("${jwt.secret}")
    private String jwtSecret;

    // ---------- Forma del cuerpo de 401 / 403 ----------

    // Verifica que sin token se responda 401 con {status, error, message, timestamp}
    @Test
    void protectedEndpoint_withoutToken_shouldReturn401WithErrorResponse() throws Exception {
        assertErrorShape(mvc.perform(get("/api/v1/users/me")), 401);
    }

    // Verifica que el 403 de una regla de URL (DISPATCHER en /admin/users) tenga la misma forma
    @Test
    void urlRule_wrongRole_shouldReturn403WithErrorResponse() throws Exception {
        String token = tokenFor("disp.e2e@test.com", User.Role.DISPATCHER);

        assertErrorShape(mvc.perform(get("/api/v1/admin/users").header("Authorization", bearer(token))), 403)
                .andExpect(jsonPath("$.message").value(FORBIDDEN_MESSAGE));
    }

    // Verifica que el 403 de @PreAuthorize (PASSENGER consultando un QR) tenga la misma forma
    @Test
    void preAuthorize_wrongRole_shouldReturn403WithErrorResponse() throws Exception {
        String token = tokenFor("pax.e2e@test.com", User.Role.PASSENGER);

        assertErrorShape(mvc.perform(get("/api/v1/tickets/qr/{qr}", "QR-INEXISTENTE")
                        .header("Authorization", bearer(token))), 403)
                .andExpect(jsonPath("$.message").value(FORBIDDEN_MESSAGE));
    }

    // Verifica que los acentos del cuerpo se decodifiquen como UTF-8
    @Test
    void errorBody_withAccents_shouldBeUtf8() throws Exception {
        String token = tokenFor("disp.utf8@test.com", User.Role.DISPATCHER);

        String body = mvc.perform(get("/api/v1/admin/users").header("Authorization", bearer(token)))
                .andExpect(status().isForbidden())
                .andExpect(header().string("Content-Type", containsString("UTF-8")))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(om.readTree(body).get("message").asText()).isEqualTo(FORBIDDEN_MESSAGE).contains("acción");
    }

    // ---------- Tokens inválidos ----------

    // Verifica que un token expirado (firmado con el secreto real) responda 401
    @Test
    void expiredToken_shouldReturn401() throws Exception {
        tokenFor("expirado@test.com", User.Role.PASSENGER);
        Instant issuedAt = Instant.now().minusSeconds(7200);
        String expired = JWT.create()
                .withSubject("expirado@test.com")
                .withClaim("role", "PASSENGER")
                .withIssuedAt(Date.from(issuedAt))
                .withExpiresAt(Date.from(issuedAt.plusSeconds(3600)))
                .sign(Algorithm.HMAC512(jwtSecret));

        assertErrorShape(mvc.perform(get("/api/v1/users/me").header("Authorization", bearer(expired))), 401);
    }

    // Verifica que un token mal formado o un esquema distinto de Bearer respondan 401
    @ParameterizedTest
    @ValueSource(strings = {"Bearer abc.def", "Basic dXN1YXJpbzpjbGF2ZQ==", "Bearer ", "bearer abc.def.ghi"})
    void malformedOrNonBearerAuthorization_shouldReturn401(String authorization) throws Exception {
        assertErrorShape(mvc.perform(get("/api/v1/users/me").header("Authorization", authorization)), 401);
    }

    // Verifica que un token firmado con otro secreto responda 401
    @Test
    void tokenSignedWithAnotherSecret_shouldReturn401() throws Exception {
        tokenFor("falso@test.com", User.Role.ADMIN);
        String forged = JWT.create()
                .withSubject("falso@test.com")
                .withClaim("role", "ADMIN")
                .withIssuedAt(new Date())
                .withExpiresAt(Date.from(Instant.now().plusSeconds(3600)))
                .sign(Algorithm.HMAC512("otro-secreto-que-no-es-el-del-servidor-0123456789"));

        mvc.perform(get("/api/v1/admin/users").header("Authorization", bearer(forged)))
                .andExpect(status().isUnauthorized());
    }

    // Verifica que un usuario desactivado con token vigente reciba 401 "Usuario inactivo" incluso en endpoints públicos
    @Test
    void inactiveUserWithValidToken_shouldReturn401UserInactive() throws Exception {
        String token = tokenFor("inactivo@test.com", User.Role.PASSENGER);
        User user = userRepository.findByEmail("inactivo@test.com").orElseThrow();
        user.setStatus(User.Status.INACTIVE);
        userRepository.saveAndFlush(user);

        assertErrorShape(mvc.perform(get("/api/v1/users/me").header("Authorization", bearer(token))), 401)
                .andExpect(jsonPath("$.message").value("Usuario inactivo"));
        mvc.perform(get("/api/v1/routes").header("Authorization", bearer(token)))
                .andExpect(status().isUnauthorized());
    }

    // ---------- Revocación por cambio de contraseña ----------

    // Verifica que tras cambiar la contraseña el token anterior deje de valer y uno nuevo sí funcione
    @Test
    void changePassword_shouldInvalidateTokensIssuedBefore() throws Exception {
        createUser(new RegisterRequest("Cambio", "cambio@test.com", "300", PASSWORD, User.Role.PASSENGER));
        // Token emitido hace un minuto (el "iat" tiene precisión de segundos)
        String oldToken = tokenIssuedAt("cambio@test.com", "PASSENGER", Instant.now().minusSeconds(60));
        mvc.perform(get("/api/v1/users/me").header("Authorization", bearer(oldToken)))
                .andExpect(status().isOk());

        mvc.perform(put("/api/v1/users/me/password")
                        .header("Authorization", bearer(oldToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new PasswordChangeRequest(PASSWORD, "nueva-clave-456"))))
                .andExpect(status().isNoContent());

        assertThat(userRepository.findByEmail("cambio@test.com").orElseThrow().getPasswordChangedAt()).isNotNull();
        assertErrorShape(mvc.perform(get("/api/v1/users/me").header("Authorization", bearer(oldToken))), 401)
                .andExpect(jsonPath("$.message").value(containsString("cambio de contraseña")));

        String newToken = login("cambio@test.com", "nueva-clave-456");
        mvc.perform(get("/api/v1/users/me").header("Authorization", bearer(newToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("cambio@test.com"));
    }

    // Verifica que la nueva contraseña igual a la actual sea un 400
    @Test
    void changePassword_withSamePassword_shouldReturn400() throws Exception {
        String token = tokenFor("misma@test.com", User.Role.PASSENGER);

        mvc.perform(put("/api/v1/users/me/password")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new PasswordChangeRequest(PASSWORD, PASSWORD))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.newPasswordDifferent").exists());
    }

    // ---------- Matriz de roles ----------

    // Verifica que cada rol solo use los endpoints de despacho que le corresponden (regla de URL, antes del servicio)
    @Test
    void dispatchEndpoints_withWrongRole_shouldReturn403() throws Exception {
        String admin = tokenFor("admin.matriz@test.com", User.Role.ADMIN);
        String clerk = tokenFor("clerk.matriz@test.com", User.Role.CLERK);
        String dispatcher = tokenFor("disp.matriz@test.com", User.Role.DISPATCHER);

        // ADMIN no asigna buses/conductores (es tarea del DISPATCHER)
        mvc.perform(post("/api/v1/trips/{id}/assign", 999999L).header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        // CLERK no abre ni cierra el abordaje
        mvc.perform(post("/api/v1/trips/{id}/boarding/close", 999999L).header("Authorization", bearer(clerk)))
                .andExpect(status().isForbidden());
        // DISPATCHER no registra la salida (la autentica el DRIVER)
        mvc.perform(post("/api/v1/trips/{id}/depart", 999999L).header("Authorization", bearer(dispatcher)))
                .andExpect(status().isForbidden());
    }

    // Verifica que un PASSENGER no acceda a la lista de pasajeros, a la consulta por QR ni a crear encomiendas
    @Test
    void staffEndpoints_withPassenger_shouldReturn403() throws Exception {
        String passenger = tokenFor("pax.matriz@test.com", User.Role.PASSENGER);

        mvc.perform(get("/api/v1/trips/{id}/passengers", 999999L)
                        .param("fromStopId", "1").param("toStopId", "2")
                        .header("Authorization", bearer(passenger)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/tickets/qr/{qr}", "QR-CUALQUIERA").header("Authorization", bearer(passenger)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/parcels").header("Authorization", bearer(passenger))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    // ---------- Registro ----------

    // Verifica que un anónimo que pide rol ADMIN reciba 403 ROLE_NOT_ALLOWED y no se cree el usuario
    @Test
    void anonymousRegister_withAdminRole_shouldReturn403() throws Exception {
        assertErrorShape(mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(
                                new RegisterRequest("Intruso", "intruso.e2e@test.com", "300", PASSWORD, User.Role.ADMIN)))), 403);

        assertThat(userRepository.findByEmail("intruso.e2e@test.com")).isEmpty();
    }

    // Verifica que un PASSENGER autenticado tampoco pueda registrar personal
    @Test
    void passengerRegister_withClerkRole_shouldReturn403() throws Exception {
        String passenger = tokenFor("pax.registro@test.com", User.Role.PASSENGER);

        mvc.perform(post("/api/v1/auth/register")
                        .header("Authorization", bearer(passenger))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(
                                new RegisterRequest("Taquilla", "taquilla.e2e@test.com", "300", PASSWORD, User.Role.CLERK))))
                .andExpect(status().isForbidden());
    }

    // Verifica que un ADMIN autenticado pueda registrar cualquier rol
    @Test
    void adminRegister_withAnyRole_shouldKeepRequestedRole() throws Exception {
        String admin = tokenFor("admin.registro@test.com", User.Role.ADMIN);

        mvc.perform(post("/api/v1/auth/register")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(
                                new RegisterRequest("Otro admin", "admin2.registro@test.com", "300", PASSWORD, User.Role.ADMIN))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    // Verifica que una contraseña corta o un nombre de más de 100 caracteres sean 400 (y no 409 de integridad)
    @Test
    void register_withShortPasswordOrTooLongName_shouldReturn400() throws Exception {
        mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new RegisterRequest("Ana", "corta@test.com", "300", "1234567", null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.password").exists());

        mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(
                                new RegisterRequest("n".repeat(101), "largo@test.com", "300", PASSWORD, null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.name").exists());
    }

    // ---------- Utilidades ----------

    private ResultActions assertErrorShape(ResultActions result, int expectedStatus) throws Exception {
        return result
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.status").value(expectedStatus))
                .andExpect(jsonPath("$.error").isNotEmpty())
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    // Crea el usuario en BD e inicia sesión por la API
    private String tokenFor(String email, User.Role role) throws Exception {
        createUser(new RegisterRequest("Usuario " + role, email, "300", PASSWORD, role));
        return login(email, PASSWORD);
    }

    private String login(String email, String password) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new LoginRequest(email, password))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode json = om.readTree(body);
        return json.get("token").asText();
    }

    // Token firmado con el secreto real y un "iat" concreto
    private String tokenIssuedAt(String email, String role, Instant issuedAt) {
        return JWT.create()
                .withSubject(email)
                .withClaim("role", role)
                .withIssuedAt(Date.from(issuedAt))
                .withExpiresAt(Date.from(issuedAt.plusSeconds(3600)))
                .sign(Algorithm.HMAC512(jwtSecret));
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
