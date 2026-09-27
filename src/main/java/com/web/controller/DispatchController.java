package com.web.controller;

import com.web.dto.baggage.TripBaggageSummaryResponse;
import com.web.dto.dispatch.Assignment.AssignmentCreateRequest;
import com.web.dto.dispatch.Assignment.AssignmentResponse;
import com.web.dto.dispatch.Assignment.AssignmentUpdateRequest;
import com.web.dto.dispatch.OverbookingApprovalResponse;
import com.web.dto.trip.TripResponse;
import com.web.exception.BusinessException;
import com.web.service.dispatch.AssignmentService;
import com.web.service.dispatch.BaggageSummaryService;
import com.web.service.dispatch.BoardingService;
import com.web.service.dispatch.OverbookingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;


@RestController
@RequestMapping("/api/v1/trips/{tripId}")
@RequiredArgsConstructor
public class DispatchController {

    private final AssignmentService assignmentService;
    private final BoardingService boardingService;
    private final OverbookingService overbookingService;
    private final BaggageSummaryService baggageSummaryService;

    // Asigna un conductor y despachador a un viaje
    @PostMapping("/assign")
    @PreAuthorize("hasRole('DISPATCHER')")
    public ResponseEntity<AssignmentResponse> assignTrip(
            @PathVariable Long tripId,
            @RequestBody @Valid AssignmentCreateRequest request) {

        if (request.tripId() != null && !request.tripId().equals(tripId)) {
            throw new BusinessException(
                    "El tripId de la URL no coincide con el del body",
                    HttpStatus.BAD_REQUEST,
                    "TRIP_ID_MISMATCH");
        }

        AssignmentCreateRequest validatedRequest = new AssignmentCreateRequest(
                tripId,
                request.driverId(),
                request.dispatcherId());

        AssignmentResponse response = assignmentService.assignTrip(validatedRequest);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    // Consulta la asignación (conductor, despachador y checklist) de un viaje
    @GetMapping("/assignment")
    @PreAuthorize("hasAnyRole('DISPATCHER', 'DRIVER')")
    public ResponseEntity<AssignmentResponse> getAssignment(@PathVariable Long tripId) {
        return ResponseEntity.ok(assignmentService.getAssignmentByTrip(tripId));
    }

    // Actualiza el checklist de salida (checklist, SOAT, revisión técnica) o el conductor asignado
    @PutMapping("/assignment")
    @PreAuthorize("hasRole('DISPATCHER')")
    public ResponseEntity<AssignmentResponse> updateAssignment(
            @PathVariable Long tripId,
            @RequestBody AssignmentUpdateRequest request) {

        AssignmentResponse current = assignmentService.getAssignmentByTrip(tripId);
        AssignmentResponse response = assignmentService.updateChecklist(current.id(), request);
        return ResponseEntity.ok(response);
    }

    // Abre o cierra el abordaje de un viaje
    @PostMapping("/boarding/{action}")
    @PreAuthorize("hasRole('DISPATCHER')")
    public ResponseEntity<TripResponse> controlBoarding(
            @PathVariable Long tripId,
            @PathVariable String action) {

        if (!action.equals("open") && !action.equals("close")) {
            throw new BusinessException("Action debe ser 'open' o 'close'",
                    HttpStatus.BAD_REQUEST, "INVALID_ACTION");
        }

        TripResponse response = action.equals("open")
                ? boardingService.openBoarding(tripId)
                : boardingService.closeBoarding(tripId);

        return ResponseEntity.ok(response);
    }

    // Marca un viaje como partido
    @PostMapping("/depart")
    @PreAuthorize("hasRole('DRIVER')")
    public ResponseEntity<TripResponse> departTrip(
            @PathVariable Long tripId) {

        TripResponse response = boardingService.departTrip(tripId);
        return ResponseEntity.ok(response);
    }

    // Registra la llegada del viaje (conductor asignado)
    @PostMapping("/arrive")
    @PreAuthorize("hasRole('DRIVER')")
    public ResponseEntity<TripResponse> arriveTrip(@PathVariable Long tripId) {
        return ResponseEntity.ok(boardingService.arriveTrip(tripId));
    }

    // Aprueba una silla extra de overbooking (ocupación > 95 % y menos de 30 min para salir)
    @PostMapping("/overbooking/approve")
    @PreAuthorize("hasRole('DISPATCHER')")
    public ResponseEntity<OverbookingApprovalResponse> approveOverbooking(@PathVariable Long tripId) {
        return ResponseEntity.ok(overbookingService.approveExtraSeat(tripId));
    }

    // Conteo de equipaje del viaje (maletero / panel de despacho)
    @GetMapping("/baggage")
    @PreAuthorize("hasAnyRole('DISPATCHER', 'DRIVER', 'CLERK')")
    public ResponseEntity<TripBaggageSummaryResponse> getTripBaggage(@PathVariable Long tripId) {
        return ResponseEntity.ok(baggageSummaryService.getTripBaggage(tripId));
    }
}
