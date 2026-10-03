package com.web.controller;

import com.web.dto.dispatch.OverbookingPolicy.OverbookingPolicyCreateRequest;
import com.web.dto.dispatch.OverbookingPolicy.OverbookingPolicyResponse;
import com.web.service.dispatch.OverbookingPolicyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// % de overbooking por ruta y franja horaria de salida
@RestController
@RequestMapping("/api/v1")
@PreAuthorize("hasAnyRole('DISPATCHER', 'ADMIN')")
@RequiredArgsConstructor
public class OverbookingPolicyController {

    private final OverbookingPolicyService policyService;

    @GetMapping("/routes/{routeId}/overbooking-policies")
    public ResponseEntity<List<OverbookingPolicyResponse>> getPolicies(@PathVariable Long routeId) {
        return ResponseEntity.ok(policyService.getPoliciesByRoute(routeId));
    }

    @PostMapping("/routes/{routeId}/overbooking-policies")
    public ResponseEntity<OverbookingPolicyResponse> createPolicy(
            @PathVariable Long routeId,
            @Valid @RequestBody OverbookingPolicyCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(policyService.createPolicy(routeId, request));
    }

    @DeleteMapping("/overbooking-policies/{id}")
    public ResponseEntity<Void> deletePolicy(@PathVariable Long id) {
        policyService.deletePolicy(id);
        return ResponseEntity.noContent().build();
    }
}
