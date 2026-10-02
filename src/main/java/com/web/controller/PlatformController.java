package com.web.controller;

import com.web.dto.notification.PlatformUpdateRequest;
import com.web.dto.notification.PlatformUpdateResponse;
import com.web.service.dispatch.PlatformService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/trips/{tripId}/platform")
@RequiredArgsConstructor
public class PlatformController {

    private final PlatformService platformService;

    // Asigna o cambia el andén de salida; si cambió, se notifica a los pasajeros (WhatsApp/SMS simulado)
    @PutMapping
    @PreAuthorize("hasRole('DISPATCHER')")
    public ResponseEntity<PlatformUpdateResponse> updatePlatform(
            @PathVariable Long tripId,
            @RequestBody @Valid PlatformUpdateRequest request) {
        return ResponseEntity.ok(platformService.updatePlatform(tripId, request.platform()));
    }
}
