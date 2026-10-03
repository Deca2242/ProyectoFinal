package com.web.controller;

import com.web.dto.catalog.Bus.BusCreateRequest;
import com.web.dto.catalog.Bus.BusResponse;
import com.web.dto.catalog.Bus.BusUpdateRequest;
import com.web.dto.catalog.Seat.SeatResponse;
import com.web.dto.catalog.Seat.SeatUpdateRequest;
import com.web.exception.BusinessException;
import com.web.service.catalog.BusService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

// Controlador para gestión de buses
@RestController
@RequestMapping("/api/v1/buses")
@RequiredArgsConstructor
public class BusController {
    
    private final BusService busService;
    
    // Crea un nuevo bus
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BusResponse> createBus(@Valid @RequestBody BusCreateRequest request) {
        BusResponse response = busService.createBus(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
    
    // Obtiene todos los buses
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'DISPATCHER')")
    public ResponseEntity<List<BusResponse>> getAllBuses() {
        return ResponseEntity.ok(busService.getAllBuses());
    }
    
    // Obtiene un bus por su ID
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'DISPATCHER')")
    public ResponseEntity<BusResponse> getBusById(@PathVariable Long id) {
        return ResponseEntity.ok(busService.getBusById(id));
    }

    // Obtiene un bus por su placa
    @GetMapping("/plate/{plate}")
    @PreAuthorize("hasAnyRole('ADMIN', 'DISPATCHER')")
    public ResponseEntity<BusResponse> getBusByPlate(@PathVariable String plate) {
        return ResponseEntity.ok(busService.getBusByPlate(plate));
    }
    
    // Obtiene buses disponibles: por franja horaria (departureTime + arrivalEta) o, si solo hay fecha, por día
    @GetMapping("/available")
    @PreAuthorize("hasAnyRole('ADMIN', 'DISPATCHER')")
    public ResponseEntity<List<BusResponse>> getAvailableBuses(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime departureTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime arrivalEta) {
        if (date == null && departureTime == null && arrivalEta == null) {
            throw new BusinessException("Debe proporcionar la fecha o la franja horaria (departureTime y arrivalEta)",
                    HttpStatus.BAD_REQUEST, "MISSING_SEARCH_PARAMS");
        }
        return ResponseEntity.ok(busService.getAvailableBuses(date, departureTime, arrivalEta));
    }

    // Sillas físicas del bus
    @GetMapping("/{id}/seats")
    @PreAuthorize("hasAnyRole('ADMIN', 'DISPATCHER')")
    public ResponseEntity<List<SeatResponse>> getSeats(@PathVariable Long id) {
        return ResponseEntity.ok(busService.getSeats(id));
    }

    // Cambia el tipo de una silla (p. ej. PREFERENTIAL)
    @PutMapping("/{id}/seats/{seatNumber}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<SeatResponse> updateSeat(
            @PathVariable Long id,
            @PathVariable Integer seatNumber,
            @Valid @RequestBody SeatUpdateRequest request) {
        return ResponseEntity.ok(busService.updateSeat(id, seatNumber, request));
    }
    
    // Actualiza un bus existente
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BusResponse> updateBus(
            @PathVariable Long id, 
            @Valid @RequestBody BusUpdateRequest request) {
        BusResponse response = busService.updateBus(id, request);
        return ResponseEntity.ok(response);
    }
    
    // Elimina un bus
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteBus(@PathVariable Long id) {
        busService.deleteBus(id);
        return ResponseEntity.noContent().build();
    }
}

