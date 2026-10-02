package com.web.controller;

import com.web.dto.incident.IncidentCreateRequest;
import com.web.dto.incident.IncidentResponse;
import com.web.entity.Incident;
import com.web.service.incident.IncidentService;
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
@RequestMapping("/api/v1/incidents")
@RequiredArgsConstructor
public class IncidentController {

    private final IncidentService incidentService;

    // Reporte de un incidente por el personal en operación
    @PostMapping
    @PreAuthorize("hasAnyRole('DRIVER', 'DISPATCHER', 'CLERK')")
    public ResponseEntity<IncidentResponse> reportIncident(@Valid @RequestBody IncidentCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(incidentService.reportIncident(request));
    }

    // Consulta de incidentes con filtros opcionales (from/to: fechas inclusivas)
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'DISPATCHER')")
    public ResponseEntity<List<IncidentResponse>> searchIncidents(
            @RequestParam(required = false) Incident.IncidentType type,
            @RequestParam(required = false) Incident.EntityType entityType,
            @RequestParam(required = false) Long entityId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(incidentService.searchIncidents(type, entityType, entityId, from, to));
    }
}
