package com.web.controller;

import com.web.config.CustomUserDetailsService;
import com.web.config.SecurityConfig;
import com.web.dto.notification.NotificationResponse;
import com.web.entity.Notification;
import com.web.exception.BusinessException;
import com.web.exception.ResourceNotFoundException;
import com.web.service.notification.NotificationService;
import com.web.util.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(NotificationController.class)
@Import(SecurityConfig.class)
class NotificationControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private NotificationService notificationService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private CustomUserDetailsService customUserDetailsService;

    private static NotificationResponse notification(Long id, Notification.NotificationType type) {
        return new NotificationResponse(id, 1L, Notification.Channel.WHATSAPP, type, "3001234567",
                "mensaje " + id, 5L, 10L, Notification.NotificationStatus.SENT, LocalDateTime.now(), null);
    }

    // Verifica que cualquier usuario autenticado vea sus notificaciones
    @ParameterizedTest
    @ValueSource(strings = {"PASSENGER", "CLERK", "DRIVER", "DISPATCHER", "ADMIN"})
    void getMyNotifications_shouldReturn200ForAnyAuthenticatedUser(String role) throws Exception {
        when(notificationService.getMyNotifications(false)).thenReturn(List.of(
                notification(2L, Notification.NotificationType.PLATFORM_CHANGED),
                notification(1L, Notification.NotificationType.TICKET_PURCHASED)));

        mvc.perform(get("/api/v1/notifications/me").with(user("ana@test.com").roles(role)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].type").value("PLATFORM_CHANGED"))
                .andExpect(jsonPath("$[0].channel").value("WHATSAPP"))
                .andExpect(jsonPath("$[1].ticketId").value(10));
    }

    // Verifica el filtro de no leídas
    @Test
    @WithMockUser(roles = "PASSENGER")
    void getMyNotifications_shouldPassUnreadOnlyFilter() throws Exception {
        when(notificationService.getMyNotifications(true))
                .thenReturn(List.of(notification(3L, Notification.NotificationType.ARRIVAL_SOON)));

        mvc.perform(get("/api/v1/notifications/me").param("unreadOnly", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].readAt").doesNotExist());

        verify(notificationService).getMyNotifications(true);
    }

    // Verifica que el dueño marque su notificación como leída
    @ParameterizedTest
    @ValueSource(strings = {"PASSENGER", "DRIVER", "ADMIN"})
    void markAsRead_shouldReturn200ForAnyAuthenticatedUser(String role) throws Exception {
        NotificationResponse read = new NotificationResponse(7L, 1L, Notification.Channel.SMS,
                Notification.NotificationType.PLATFORM_CHANGED, "6015551234", "andén", 5L, 10L,
                Notification.NotificationStatus.SENT, LocalDateTime.now(), LocalDateTime.now());
        when(notificationService.markAsRead(7L)).thenReturn(read);

        mvc.perform(patch("/api/v1/notifications/7/read").with(user("ana@test.com").roles(role)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.readAt").isNotEmpty());
    }

    // Verifica 403 al marcar una notificación ajena y 404 si no existe
    @Test
    @WithMockUser(roles = "PASSENGER")
    void markAsRead_shouldReturn403ForOtherUsersNotification_and404WhenUnknown() throws Exception {
        when(notificationService.markAsRead(8L)).thenThrow(new BusinessException(
                "La notificación no pertenece al usuario autenticado", HttpStatus.FORBIDDEN, "NOT_NOTIFICATION_OWNER"));
        when(notificationService.markAsRead(99L)).thenThrow(new ResourceNotFoundException("Notificación", 99L));

        mvc.perform(patch("/api/v1/notifications/8/read").with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/v1/notifications/99/read").with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    void markAsRead_shouldReturn401WhenAnonymous() throws Exception {
        mvc.perform(patch("/api/v1/notifications/7/read").with(csrf()))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(notificationService);
    }

    // Verifica que sin autenticación se responda 401
    @Test
    void getMyNotifications_shouldReturn401WhenAnonymous() throws Exception {
        mvc.perform(get("/api/v1/notifications/me"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(notificationService);
    }

    // Verifica que un ADMIN vea todas las notificaciones
    @Test
    @WithMockUser(roles = "ADMIN")
    void getNotifications_shouldReturn200WithAllForAdmin() throws Exception {
        when(notificationService.getNotifications(null))
                .thenReturn(List.of(notification(1L, Notification.NotificationType.ARRIVAL_SOON)));

        mvc.perform(get("/api/v1/admin/notifications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("ARRIVAL_SOON"));

        verify(notificationService).getNotifications(null);
    }

    // Verifica el filtro por viaje
    @Test
    @WithMockUser(roles = "ADMIN")
    void getNotifications_shouldFilterByTrip() throws Exception {
        when(notificationService.getNotifications(5L))
                .thenReturn(List.of(notification(1L, Notification.NotificationType.TRIP_CANCELLED)));

        mvc.perform(get("/api/v1/admin/notifications").param("tripId", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].tripId").value(5));

        verify(notificationService).getNotifications(5L);
    }

    // Verifica 400 cuando el tripId no es numérico
    @Test
    @WithMockUser(roles = "ADMIN")
    void getNotifications_shouldReturn400WhenTripIdInvalid() throws Exception {
        mvc.perform(get("/api/v1/admin/notifications").param("tripId", "abc"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(notificationService);
    }

    // Verifica que solo el ADMIN consulte todas las notificaciones
    @ParameterizedTest
    @ValueSource(strings = {"PASSENGER", "CLERK", "DRIVER", "DISPATCHER"})
    void getNotifications_shouldReturn403ForOtherRoles(String role) throws Exception {
        mvc.perform(get("/api/v1/admin/notifications").with(user("user").roles(role)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(notificationService);
    }

    // Verifica que sin autenticación se responda 401
    @Test
    void getNotifications_shouldReturn401WhenAnonymous() throws Exception {
        mvc.perform(get("/api/v1/admin/notifications"))
                .andExpect(status().isUnauthorized());
    }
}
