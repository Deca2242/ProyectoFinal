package com.web.controller;

import com.web.dto.admin.ConfigResponse;
import com.web.dto.admin.ConfigUpdateRequest;
import com.web.dto.admin.MetricsResponse;
import com.web.repository.UserRepository;
import com.web.service.admin.ConfigService;
import com.web.service.admin.MetricsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;


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

    // Obtiene el ID del usuario autenticado desde el contexto de seguridad
    // KPIs: ocupación, ingresos, puntualidad, no-show, cancelaciones y encomiendas en un rango de fechas
    @GetMapping("/metrics")
    public ResponseEntity<MetricsResponse> getMetrics(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ResponseEntity.ok(metricsService.getMetrics(startDate, endDate));
    }

    private Long getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String email = authentication.getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado: " + email))
                .getId();
    }
}
