package com.web.controller;

import com.web.config.CustomUserDetailsService;
import com.web.config.SecurityConfig;
import com.web.dto.notification.NotificationResponse;
import com.web.entity.Notification;
import com.web.service.notification.NotificationService;
import com.web.util.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
                "mensaje " + id, 5L, 10L, Notification.NotificationStatus.SENT, LocalDateTime.now());
    }

    // Verifica que cualquier usuario autenticado vea sus notificaciones
    @ParameterizedTest
    @ValueSource(strings = {"PASSENGER", "CLERK", "DRIVER", "DISPATCHER", "ADMIN"})
    void getMyNotifications_shouldReturn200ForAnyAuthenticatedUser(String role) throws Exception {
        when(notificationService.getMyNotifications()).thenReturn(List.of(
                notification(2L, Notification.NotificationType.PLATFORM_CHANGED),
                notification(1L, Notification.NotificationType.TICKET_PURCHASED)));

        mvc.perform(get("/api/v1/notifications/me").with(user("ana@test.com").roles(role)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].type").value("PLATFORM_CHANGED"))
                .andExpect(jsonPath("$[0].channel").value("WHATSAPP"))
                .andExpect(jsonPath("$[1].ticketId").value(10));
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
