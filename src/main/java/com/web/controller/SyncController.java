package com.web.controller;

import com.web.dto.sync.BoardingSyncRequest;
import com.web.dto.sync.SyncBatchResponse;
import com.web.dto.sync.SyncConflictResponse;
import com.web.dto.sync.TicketSyncRequest;
import com.web.service.sync.SyncService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// Operación offline: la taquilla y el conductor envían lo registrado sin red al recuperar la señal
@RestController
@RequestMapping("/api/v1/sync")
@RequiredArgsConstructor
public class SyncController {

    private final SyncService syncService;

    // Ventas offline (pendingSync); reenviar el mismo lote no duplica sillas
    @PostMapping("/tickets")
    @PreAuthorize("hasAnyRole('CLERK', 'DRIVER')")
    public ResponseEntity<SyncBatchResponse> syncTickets(@RequestBody @Valid TicketSyncRequest request) {
        return ResponseEntity.ok(syncService.syncTickets(request));
    }

    // Abordajes validados offline con su hora real
    @PostMapping("/boardings")
    @PreAuthorize("hasAnyRole('DRIVER', 'DISPATCHER')")
    public ResponseEntity<SyncBatchResponse> syncBoardings(@RequestBody @Valid BoardingSyncRequest request) {
        return ResponseEntity.ok(syncService.syncBoardings(request));
    }

    // Operaciones rechazadas al sincronizar
    @GetMapping("/conflicts")
    @PreAuthorize("hasAnyRole('CLERK', 'DRIVER', 'DISPATCHER', 'ADMIN')")
    public ResponseEntity<List<SyncConflictResponse>> getConflicts(@RequestParam(required = false) String deviceId) {
        return ResponseEntity.ok(syncService.getConflicts(deviceId));
    }
}
