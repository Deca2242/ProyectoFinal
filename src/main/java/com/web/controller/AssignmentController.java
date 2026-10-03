package com.web.controller;

import com.web.dto.dispatch.Assignment.AssignmentResponse;
import com.web.service.dispatch.AssignmentService;
import com.web.service.user.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

// Asignaciones del usuario autenticado: las del conductor y las que hizo el despachador
@RestController
@RequestMapping("/api/v1/assignments")
@RequiredArgsConstructor
public class AssignmentController {

    private final AssignmentService assignmentService;
    private final UserService userService;

    // Asignaciones del conductor autenticado (todas o las de una fecha)
    @GetMapping("/me")
    @PreAuthorize("hasRole('DRIVER')")
    public ResponseEntity<List<AssignmentResponse>> getMyAssignments(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        Long driverId = userService.getCurrentUser().id();
        return ResponseEntity.ok(assignmentService.getDriverAssignments(driverId, date));
    }

    // Asignaciones hechas por el despachador autenticado (desde hoy o las de una fecha)
    @GetMapping
    @PreAuthorize("hasRole('DISPATCHER')")
    public ResponseEntity<List<AssignmentResponse>> getDispatcherAssignments(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        Long dispatcherId = userService.getCurrentUser().id();
        return ResponseEntity.ok(assignmentService.getDispatcherAssignments(dispatcherId, date));
    }
}
