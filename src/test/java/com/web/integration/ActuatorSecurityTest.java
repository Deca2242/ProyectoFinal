package com.web.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.dto.auth.Login.LoginRequest;
import com.web.dto.auth.Login.RegisterRequest;
import com.web.entity.User;
import com.web.integration.userstories.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Observabilidad: el healthcheck es público y el resto de Actuator (métricas de Micrometer) solo para ADMIN
class ActuatorSecurityTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper om;

    // Verifica que el healthcheck responda 200 sin token (lo usan balanceadores y orquestadores)
    @Test
    void health_withoutToken_shouldReturn200() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    // Verifica que las métricas sin token respondan 401 con el ErrorResponse uniforme
    @Test
    void metrics_withoutToken_shouldReturn401() throws Exception {
        mvc.perform(get("/actuator/metrics"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").exists());
    }

    // Verifica que un ADMIN vea las métricas y un rol operativo reciba 403
    @Test
    void metrics_withAdminToken_shouldReturn200_andOtherRolesForbidden() throws Exception {
        createUser(new RegisterRequest("Admin", "admin.actuator@test.com", "300", "secreto123", User.Role.ADMIN));
        createUser(new RegisterRequest("Despacho", "disp.actuator@test.com", "300", "secreto123", User.Role.DISPATCHER));

        mvc.perform(get("/actuator/metrics")
                        .header("Authorization", "Bearer " + login("admin.actuator@test.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.names").isArray());

        mvc.perform(get("/actuator/metrics/jvm.memory.used")
                        .header("Authorization", "Bearer " + login("admin.actuator@test.com")))
                .andExpect(status().isOk());

        mvc.perform(get("/actuator/metrics")
                        .header("Authorization", "Bearer " + login("disp.actuator@test.com")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    private String login(String email) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new LoginRequest(email, "secreto123"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return om.readTree(body).get("token").asText();
    }
}
