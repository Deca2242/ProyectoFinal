package com.web.controller;

import com.web.dto.notification.NotificationResponse;
import com.web.service.notification.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    // Notificaciones del usuario autenticado, más recientes primero
    @GetMapping("/notifications/me")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<NotificationResponse>> getMyNotifications() {
        return ResponseEntity.ok(notificationService.getMyNotifications());
    }

    // Todas las notificaciones enviadas, o solo las de un viaje
    @GetMapping("/admin/notifications")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<NotificationResponse>> getNotifications(
            @RequestParam(required = false) Long tripId) {
        return ResponseEntity.ok(notificationService.getNotifications(tripId));
    }
}
