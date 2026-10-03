package com.web.controller;

import com.web.dto.baggage.BaggageCreateRequest;
import com.web.dto.baggage.BaggageResponse;
import com.web.service.baggage.BaggageService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

// Equipaje de un ticket ya vendido (registro en taquilla después de la compra)
@RestController
@RequestMapping("/api/v1/tickets/{ticketId}/baggage")
@RequiredArgsConstructor
public class BaggageController {

    private final BaggageService baggageService;

    // Registra el equipaje: peso, cargo por exceso, etiqueta y maletero
    @PostMapping
    @PreAuthorize("hasAnyRole('CLERK', 'ADMIN')")
    public ResponseEntity<BaggageResponse> addBaggage(@PathVariable Long ticketId,
                                                      @Valid @RequestBody BaggageCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(baggageService.addBaggage(ticketId, request));
    }

    // Consulta el equipaje del ticket (un pasajero solo el de sus tickets)
    @GetMapping
    public ResponseEntity<BaggageResponse> getBaggage(@PathVariable Long ticketId) {
        return ResponseEntity.ok(baggageService.getBaggage(ticketId));
    }

    // Retira el equipaje antes de abordar
    @DeleteMapping
    @PreAuthorize("hasRole('CLERK')")
    public ResponseEntity<Void> removeBaggage(@PathVariable Long ticketId) {
        baggageService.removeBaggage(ticketId);
        return ResponseEntity.noContent().build();
    }
}
