package com.web.controller;

import com.web.dto.ticket.TicketResponse;
import com.web.dto.trip.SeatStatusResponse;
import com.web.dto.trip.TripCreateRequest;
import com.web.dto.trip.TripDetailResponse;
import com.web.dto.trip.TripResponse;
import com.web.dto.trip.TripUpdateRequest;
import com.web.entity.Trip;
import com.web.exception.BusinessException;
import com.web.service.trip.TripService;
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
@RequestMapping("/api/v1/trips")
@RequiredArgsConstructor
public class TripController {

    private final TripService tripService;

    // Endpoints públicos (consulta)

    @GetMapping
    public ResponseEntity<List<TripResponse>> searchTrips(
            @RequestParam(required = false) Long routeId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {

        if (routeId == null && date == null) {
            throw new BusinessException("Debe proporcionar al menos routeId o date",
                    HttpStatus.BAD_REQUEST, "MISSING_SEARCH_PARAMS");
        }

        return ResponseEntity.ok(tripService.searchTrips(routeId, date));
    }

    @GetMapping("/{id}")
    public ResponseEntity<TripDetailResponse> getTripById(@PathVariable Long id) {
        return ResponseEntity.ok(tripService.getTripById(id));
    }

    @GetMapping("/{id}/seats")
    public ResponseEntity<List<SeatStatusResponse>> getSeatAvailability(
            @PathVariable Long id,
            @RequestParam Long fromStopId,
            @RequestParam Long toStopId) {
        return ResponseEntity.ok(tripService.getSeatAvailability(id, fromStopId, toStopId));
    }

    @GetMapping("/{tripId}/passengers")
    @PreAuthorize("hasAnyRole('DRIVER', 'DISPATCHER')")
    public ResponseEntity<List<TicketResponse>> getPassengersBySegment(
            @PathVariable Long tripId,
            @RequestParam Long fromStopId,
            @RequestParam Long toStopId) {
        List<TicketResponse> response = tripService.getPassengersBySegment(tripId, fromStopId, toStopId);
        return ResponseEntity.ok(response);
    }

    // Endpoints protegidos (solo ADMIN)

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<TripResponse> createTrip(@Valid @RequestBody TripCreateRequest request) {
        TripResponse response = tripService.createTrip(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<TripResponse> updateTripStatus(
            @PathVariable Long id,
            @RequestParam Trip.TripStatus status) {
        TripResponse response = tripService.updateTripStatus(id, status);
        return ResponseEntity.ok(response);
    }

    // Reprograma un viaje SCHEDULED (salida, llegada y/o bus)
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<TripResponse> rescheduleTrip(
            @PathVariable Long id,
            @RequestBody TripUpdateRequest request) {
        return ResponseEntity.ok(tripService.rescheduleTrip(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> cancelTrip(@PathVariable Long id) {
        tripService.cancelTrip(id);
        return ResponseEntity.noContent().build();
    }
}
