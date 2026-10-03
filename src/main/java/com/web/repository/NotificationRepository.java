package com.web.repository;

import com.web.entity.Notification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    // Notificaciones de un usuario, más recientes primero
    List<Notification> findByUserIdOrderByCreatedAtDescIdDesc(Long userId);

    // Notificaciones de un viaje, más recientes primero
    List<Notification> findByTripIdOrderByCreatedAtDescIdDesc(Long tripId);

    // Todas las notificaciones, más recientes primero
    List<Notification> findAllByOrderByCreatedAtDescIdDesc();

    List<Notification> findByTripIdAndType(Long tripId, Notification.NotificationType type);
}
