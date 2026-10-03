package com.web.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.dto.auth.Login.LoginRequest;
import com.web.dto.auth.Login.RegisterRequest;
import com.web.entity.Bus;
import com.web.entity.Config;
import com.web.entity.Route;
import com.web.entity.Trip;
import com.web.entity.User;
import com.web.integration.userstories.BaseIntegrationTest;
import com.web.repository.BusRepository;
import com.web.repository.ConfigRepository;
import com.web.repository.PunctualityReportRepository;
import com.web.repository.RouteRepository;
import com.web.repository.TripRepository;
import com.web.repository.UserRepository;
import com.web.service.admin.PunctualityReportScheduler;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Administración de extremo a extremo: búsqueda y detalle de usuarios, protección del último ADMIN,
// claves de configuración nuevas y reporte diario de puntualidad
class AdminOperationsIntegrationTest extends BaseIntegrationTest {

    private static final String PASSWORD = "secreto123";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper om;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ConfigRepository configRepository;

    @Autowired
    private RouteRepository routeRepository;

    @Autowired
    private BusRepository busRepository;

    @Autowired
    private TripRepository tripRepository;

    @Autowired
    private PunctualityReportRepository punctualityReportRepository;

    @Autowired
    private PunctualityReportScheduler punctualityReportScheduler;

    @Autowired
    private EntityManager entityManager;

    private String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        createUser(new RegisterRequest("Admin Operaciones", "admin.ops@test.com", "300", PASSWORD, User.Role.ADMIN));
        adminToken = login("admin.ops@test.com");
    }

    // ---------- Usuarios ----------

    // Verifica la búsqueda por texto combinada con el filtro de rol
    @Test
    void searchUsers_byTextAndRole_shouldReturnOnlyMatches() throws Exception {
        createUser(new RegisterRequest("Zacarías Buscable", "zbuscable.driver@test.com", "3009990001", PASSWORD, User.Role.DRIVER));
        createUser(new RegisterRequest("Otro Zbuscable", "otro@test.com", "3009990002", PASSWORD, User.Role.CLERK));
        createUser(new RegisterRequest("Sin coincidencia", "nada@test.com", "3009990003", PASSWORD, User.Role.DRIVER));

        mvc.perform(get("/api/v1/admin/users").param("q", "ZBUSCABLE").header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].passwordHash").doesNotExist());

        mvc.perform(get("/api/v1/admin/users")
                        .param("q", "zbuscable").param("role", "DRIVER")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].email").value("zbuscable.driver@test.com"));

        // También por teléfono
        mvc.perform(get("/api/v1/admin/users").param("q", "3009990003").header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].email").value("nada@test.com"));
    }

    // Verifica el detalle de un usuario y el 404 de uno inexistente
    @Test
    void getUser_byId_shouldReturnUserOr404() throws Exception {
        User driver = createUser(new RegisterRequest("Conductor", "detalle@test.com", "300", PASSWORD, User.Role.DRIVER));

        mvc.perform(get("/api/v1/admin/users/{id}", driver.getId()).header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("detalle@test.com"))
                .andExpect(jsonPath("$.role").value("DRIVER"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        mvc.perform(get("/api/v1/admin/users/{id}", 987654321L).header("Authorization", bearer(adminToken)))
                .andExpect(status().isNotFound());
    }

    // Verifica que no se pueda desactivar ni degradar al último ADMIN activo aunque lo pida otro administrador
    @Test
    void lastActiveAdmin_cannotBeDeactivatedNorDemoted() throws Exception {
        User target = createUser(new RegisterRequest("Último admin", "ultimo.admin@test.com", "300", PASSWORD, User.Role.ADMIN));
        // Deja a "target" como único ADMIN activo
        userRepository.findByRoleAndStatus(User.Role.ADMIN, User.Status.ACTIVE).stream()
                .filter(u -> !u.getId().equals(target.getId()))
                .forEach(u -> u.setStatus(User.Status.INACTIVE));
        userRepository.flush();

        // Un administrador de soporte (no registrado como ADMIN activo en la BD) intenta dejarlo sin acceso
        mvc.perform(patch("/api/v1/admin/users/{id}/status", target.getId())
                        .with(user("soporte@test.com").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"INACTIVE\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
        mvc.perform(patch("/api/v1/admin/users/{id}/role", target.getId())
                        .with(user("soporte@test.com").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"DISPATCHER\"}"))
                .andExpect(status().isConflict());

        User reloaded = userRepository.findById(target.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(User.Status.ACTIVE);
        assertThat(reloaded.getRole()).isEqualTo(User.Role.ADMIN);
    }

    // Verifica que con dos ADMIN activos uno sí pueda desactivar al otro
    @Test
    void adminDeactivatesAnotherAdmin_whenOthersRemain_shouldSucceed() throws Exception {
        User other = createUser(new RegisterRequest("Otro admin", "otro.admin@test.com", "300", PASSWORD, User.Role.ADMIN));

        mvc.perform(patch("/api/v1/admin/users/{id}/status", other.getId())
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"INACTIVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"));
    }

    // ---------- Configuración ----------

    // Verifica que las claves operativas nuevas se expongan, se guarden con su tipo y se puedan leer
    @Test
    void updateConfig_withOperationalLimits_shouldPersistWithDataType() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("baggageWeightMax", 45.0);
        body.put("parcelOtpMaxAttempts", 4);
        body.put("maxActiveHoldsPerUserAndTrip", 3);
        body.put("noShowWindowMinutes", 8);
        body.put("overbookingMinOccupancy", 0.9);
        body.put("overbookingWindowMinutes", 40);
        body.put("dynamicPricingDefault", true);

        mvc.perform(put("/api/v1/admin/config")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baggageWeightMax").value(45.0))
                .andExpect(jsonPath("$.parcelOtpMaxAttempts").value(4))
                .andExpect(jsonPath("$.maxActiveHoldsPerUserAndTrip").value(3))
                .andExpect(jsonPath("$.noShowWindowMinutes").value(8))
                .andExpect(jsonPath("$.overbookingMinOccupancy").value(0.9))
                .andExpect(jsonPath("$.overbookingWindowMinutes").value(40))
                .andExpect(jsonPath("$.dynamicPricingDefault").value(true));

        mvc.perform(get("/api/v1/admin/config").header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parcelOtpMaxAttempts").value(4));

        assertThat(configRepository.findByConfigKey("parcel.otp.max.attempts").orElseThrow().getDataType())
                .isEqualTo(Config.DataType.INTEGER);
        assertThat(configRepository.findByConfigKey("ticket.dynamic.pricing.default").orElseThrow().getDataType())
                .isEqualTo(Config.DataType.BOOLEAN);
        assertThat(configRepository.findByConfigKey("overbooking.min.occupancy").orElseThrow().getDataType())
                .isEqualTo(Config.DataType.DECIMAL);
    }

    // Verifica que una política de reembolso creciente se rechace con 400 sin modificar nada
    @Test
    void updateConfig_withNonMonotonicRefundPolicy_shouldReturn400() throws Exception {
        mvc.perform(put("/api/v1/admin/config")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refundPercentage48Hours\": 60, \"refundPercentage24Hours\": 70, \"holdDurationMinutes\": 99}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("no creciente")));

        assertThat(configRepository.findByConfigKey("hold.duration.minutes").orElseThrow().getConfigValue())
                .isNotEqualTo("99");
    }

    // ---------- Puntualidad ----------

    // Verifica el job diario (día anterior), la consulta de reportes y el desglose por ruta en /metrics
    @Test
    void punctualityReport_generatedByJob_shouldBeQueryableAndIdempotent() throws Exception {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        Route route = routeRepository.save(Route.builder()
                .code("PUN-" + (System.nanoTime() % 1_000_000))
                .name("Ruta puntualidad")
                .origin("Santa Marta")
                .destination("Riohacha")
                .distanceKm(new BigDecimal("170.00"))
                .durationMin(180)
                .isActive(true)
                .build());
        Bus bus = busRepository.save(Bus.builder()
                .plate("PUN" + (System.nanoTime() % 100000))
                .capacity(40)
                .amenities(new HashMap<>())
                .status(Bus.BusStatus.ACTIVE)
                .build());
        // Salió 3 min tarde (puntual) y llegó 30 min tarde
        tripRepository.save(Trip.builder().route(route).bus(bus).tripDate(yesterday)
                .departureTime(yesterday.atTime(10, 0)).arrivalEta(yesterday.atTime(14, 0))
                .departedAt(yesterday.atTime(10, 3)).arrivedAt(yesterday.atTime(14, 30))
                .status(Trip.TripStatus.ARRIVED).build());
        // Salió 20 min tarde y aún no registra llegada
        tripRepository.save(Trip.builder().route(route).bus(bus).tripDate(yesterday)
                .departureTime(yesterday.atTime(16, 0)).arrivalEta(yesterday.atTime(20, 0))
                .departedAt(yesterday.atTime(16, 20))
                .status(Trip.TripStatus.DEPARTED).build());
        entityManager.flush();
        entityManager.clear();

        // When: el job se ejecuta dos veces (regenerar no duplica filas)
        punctualityReportScheduler.generateDailyReport();
        punctualityReportScheduler.generateDailyReport();
        entityManager.flush();

        // Then
        assertThat(punctualityReportRepository.findByReportDateBetween(yesterday, yesterday))
                .filteredOn(r -> r.getRoute().getId().equals(route.getId()))
                .hasSize(1);

        String body = mvc.perform(get("/api/v1/admin/metrics/punctuality")
                        .param("from", yesterday.toString()).param("to", yesterday.toString())
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode report = findByRouteCode(om.readTree(body), route.getCode());
        assertThat(report.get("reportDate").asText()).isEqualTo(yesterday.toString());
        assertThat(report.get("trips").asInt()).isEqualTo(2);
        assertThat(report.get("onTimeDeparturePct").asDouble()).isEqualTo(50.0);
        assertThat(report.get("onTimeArrivalPct").asDouble()).isZero();
        assertThat(report.get("avgDepartureDelayMin").asDouble()).isEqualTo(11.5);
        assertThat(report.get("avgArrivalDelayMin").asDouble()).isEqualTo(30.0);

        // El KPI en vivo también trae el desglose de la ruta
        String metrics = mvc.perform(get("/api/v1/admin/metrics")
                        .param("startDate", yesterday.toString()).param("endDate", yesterday.toString())
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operational.avgDepartureDelayMin").isNumber())
                .andExpect(jsonPath("$.operational.p95DepartureDelayMin").isNumber())
                .andReturn().getResponse().getContentAsString();
        JsonNode byRoute = findByRouteCode(om.readTree(metrics).get("operational").get("punctualityByRoute"), route.getCode());
        assertThat(byRoute.get("trips").asInt()).isEqualTo(2);
        assertThat(byRoute.get("avgDepartureDelayMin").asDouble()).isEqualTo(11.5);
    }

    // Verifica que solo ADMIN consulte los reportes y que un rango invertido sea 400
    @Test
    void punctualityReports_accessAndValidation() throws Exception {
        createUser(new RegisterRequest("Despacho", "disp.pun@test.com", "300", PASSWORD, User.Role.DISPATCHER));

        mvc.perform(get("/api/v1/admin/metrics/punctuality")
                        .param("from", "2026-01-01").param("to", "2026-01-31")
                        .header("Authorization", bearer(login("disp.pun@test.com"))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/metrics/punctuality")
                        .param("from", "2026-01-31").param("to", "2026-01-01")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isBadRequest());
    }

    // ---------- Utilidades ----------

    private static JsonNode findByRouteCode(JsonNode array, String code) {
        for (JsonNode node : array) {
            if (code.equals(node.get("routeCode").asText())) {
                return node;
            }
        }
        throw new AssertionError("No hay datos de la ruta " + code + " en " + array);
    }

    private String login(String email) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new LoginRequest(email, PASSWORD))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return om.readTree(body).get("token").asText();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
