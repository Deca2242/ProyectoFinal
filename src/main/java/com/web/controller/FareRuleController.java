package com.web.controller;

import com.web.dto.catalog.FareRule.FareRuleCreateRequest;
import com.web.dto.catalog.FareRule.FareRuleResponse;
import com.web.dto.catalog.FareRule.FareRuleUpdateRequest;
import com.web.service.catalog.FareRuleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// Tarifas por tramo de una ruta (consulta pública, gestión ADMIN)
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class FareRuleController {

    private final FareRuleService fareRuleService;

    @GetMapping("/routes/{routeId}/fares")
    public ResponseEntity<List<FareRuleResponse>> getFareRules(@PathVariable Long routeId) {
        return ResponseEntity.ok(fareRuleService.getFareRulesByRoute(routeId));
    }

    @PostMapping("/routes/{routeId}/fares")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<FareRuleResponse> createFareRule(
            @PathVariable Long routeId,
            @Valid @RequestBody FareRuleCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(fareRuleService.createFareRule(routeId, request));
    }

    @PutMapping("/fares/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<FareRuleResponse> updateFareRule(
            @PathVariable Long id,
            @Valid @RequestBody FareRuleUpdateRequest request) {
        return ResponseEntity.ok(fareRuleService.updateFareRule(id, request));
    }

    @DeleteMapping("/fares/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteFareRule(@PathVariable Long id) {
        fareRuleService.deleteFareRule(id);
        return ResponseEntity.noContent().build();
    }
}
