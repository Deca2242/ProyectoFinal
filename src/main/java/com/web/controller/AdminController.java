package com.web.controller;

import com.web.dto.admin.ConfigResponse;
import com.web.dto.admin.ConfigUpdateRequest;
import com.web.dto.admin.MetricsResponse;
import com.web.dto.admin.PunctualityReportResponse;
import com.web.exception.BusinessException;
import com.web.repository.UserRepository;
import com.web.service.admin.ConfigService;
import com.web.service.admin.MetricsService;
import com.web.util.SecurityUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;


@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminController {

    private final ConfigService configService;
    private final MetricsService metricsService;
    private final UserRepository userRepository;

    // Obtiene la configuración actual del sistema
    @GetMapping("/config")
    public ResponseEntity<ConfigResponse> getConfig() {
        ConfigResponse response = configService.getConfig();
        return ResponseEntity.ok(response);
    }

    // Actualiza la configuración del sistema
    @PutMapping("/config")
    public ResponseEntity<ConfigResponse> updateConfig(@Valid @RequestBody ConfigUpdateRequest request) {
        Long userId = getCurrentUserId();
        ConfigResponse response = configService.updateConfig(request, userId);
        return ResponseEntity.ok(response);
    }

    // KPIs: ocupación, ingresos, puntualidad, no-show, cancelaciones y encomiendas en un rango de fechas
    @GetMapping("/metrics")
    public ResponseEntity<MetricsResponse> getMetrics(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ResponseEntity.ok(metricsService.getMetrics(startDate, endDate));
    }

    // Reportes diarios de puntualidad por ruta generados por el job nocturno
    @GetMapping("/metrics/punctuality")
    public ResponseEntity<List<PunctualityReportResponse>> getPunctualityReports(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(metricsService.getPunctualityReports(from, to));
    }

    // Obtiene el ID del usuario autenticado desde el contexto de seguridad (401 si ya no existe)
    private Long getCurrentUserId() {
        return SecurityUtils.currentUsername()
                .flatMap(userRepository::findByEmail)
                .orElseThrow(() -> new BusinessException("Usuario autenticado no encontrado",
                        HttpStatus.UNAUTHORIZED, "USER_NOT_FOUND"))
                .getId();
    }
}
