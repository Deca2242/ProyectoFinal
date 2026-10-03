package com.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.config.CustomUserDetailsService;
import com.web.config.SecurityConfig;
import com.web.dto.sync.BoardingSyncRequest;
import com.web.dto.sync.OfflineBoarding;
import com.web.dto.sync.OfflineTicketSale;
import com.web.dto.sync.SyncBatchResponse;
import com.web.dto.sync.SyncConflictResponse;
import com.web.dto.sync.SyncItemResult;
import com.web.dto.sync.TicketSyncRequest;
import com.web.entity.SyncBatch;
import com.web.entity.Ticket;
import com.web.exception.BusinessException;
import com.web.exception.InvalidStateTransitionException;
import com.web.exception.ResourceNotFoundException;
import com.web.service.sync.SyncService;
import com.web.util.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(SyncController.class)
@Import(SecurityConfig.class)
class SyncControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper om;

    @MockitoBean
    private SyncService syncService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private CustomUserDetailsService customUserDetailsService;

    private OfflineTicketSale sale(String clientId) {
        return new OfflineTicketSale(clientId, 1L, 2L, 10, 1L, 2L, Ticket.PaymentMethod.CASH, null,
                LocalDateTime.now().minusMinutes(10), null);
    }

    private SyncBatchResponse batchResponse() {
        return new SyncBatchResponse(100L, 2, 1, 0, 1, List.of(
                new SyncItemResult("c-1", SyncItemResult.Status.SYNCED, 5L, "QR-5", null, null),
                new SyncItemResult("c-2", SyncItemResult.Status.CONFLICT, null, null, "SEAT_NOT_AVAILABLE", "ocupado")));
    }

    // ---------- POST /sync/tickets ----------

    // Verifica que la taquilla sincronice su lote de ventas offline
    @ParameterizedTest
    @ValueSource(strings = {"CLERK", "DRIVER"})
    void syncTickets_shouldReturn200ForAllowedRoles(String role) throws Exception {
        when(syncService.syncTickets(any(TicketSyncRequest.class))).thenReturn(batchResponse());
        var req = new TicketSyncRequest("tablet-1", List.of(sale("c-1"), sale("c-2")));

        mvc.perform(post("/api/v1/sync/tickets").with(user("u").roles(role)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchId").value(100))
                .andExpect(jsonPath("$.synced").value(1))
                .andExpect(jsonPath("$.conflicts").value(1))
                .andExpect(jsonPath("$.results[0].status").value("SYNCED"))
                .andExpect(jsonPath("$.results[0].ticketId").value(5))
                .andExpect(jsonPath("$.results[0].code").doesNotExist())
                .andExpect(jsonPath("$.results[1].status").value("CONFLICT"))
                .andExpect(jsonPath("$.results[1].code").value("SEAT_NOT_AVAILABLE"));
    }

    // Verifica que un pasajero, despachador o admin no pueda sincronizar ventas
    @ParameterizedTest
    @ValueSource(strings = {"PASSENGER", "DISPATCHER", "ADMIN"})
    void syncTickets_shouldReturn403ForOtherRoles(String role) throws Exception {
        var req = new TicketSyncRequest("tablet-1", List.of(sale("c-1")));

        mvc.perform(post("/api/v1/sync/tickets").with(user("u").roles(role)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(req)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(syncService);
    }

    // Verifica que sin token responda 401
    @Test
    void syncTickets_shouldReturn401WithoutAuthentication() throws Exception {
        var req = new TicketSyncRequest("tablet-1", List.of(sale("c-1")));

        mvc.perform(post("/api/v1/sync/tickets").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(req)))
                .andExpect(status().isUnauthorized());
    }

    // Verifica la validación del lote: sin deviceId, vacío o con una venta incompleta
    @Test
    @WithMockUser(roles = "CLERK")
    void syncTickets_shouldReturn400WhenDeviceIdMissing() throws Exception {
        var req = new TicketSyncRequest(" ", List.of(sale("c-1")));

        mvc.perform(post("/api/v1/sync/tickets").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(syncService);
    }

    @Test
    @WithMockUser(roles = "CLERK")
    void syncTickets_shouldReturn400WhenSalesEmpty() throws Exception {
        var req = new TicketSyncRequest("tablet-1", List.of());

        mvc.perform(post("/api/v1/sync/tickets").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "CLERK")
    void syncTickets_shouldReturn400WhenSaleIncomplete() throws Exception {
        var incomplete = new OfflineTicketSale("c-1", 1L, 2L, 10, 1L, 2L, Ticket.PaymentMethod.CASH, null, null, null);
        var req = new TicketSyncRequest("tablet-1", List.of(incomplete));

        mvc.perform(post("/api/v1/sync/tickets").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(syncService);
    }

    @Test
    @WithMockUser(roles = "CLERK")
    void syncTickets_shouldReturn400WhenOfflineIdMissing() throws Exception {
        var noId = new OfflineTicketSale("", 1L, 2L, 10, 1L, 2L, Ticket.PaymentMethod.CASH, null,
                LocalDateTime.now(), null);
        var req = new TicketSyncRequest("tablet-1", List.of(noId));

        mvc.perform(post("/api/v1/sync/tickets").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    // Verifica el límite de 200 ventas por lote
    @Test
    @WithMockUser(roles = "CLERK")
    void syncTickets_shouldReturn400WhenBatchTooLarge() throws Exception {
        List<OfflineTicketSale> sales = new ArrayList<>();
        for (int i = 0; i <= TicketSyncRequest.MAX_BATCH_SIZE; i++) {
            sales.add(sale("c-" + i));
        }
        var req = new TicketSyncRequest("tablet-1", sales);

        mvc.perform(post("/api/v1/sync/tickets").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(syncService);
    }

    // Verifica que un error de negocio del servicio se propague con su código
    @Test
    @WithMockUser(roles = "CLERK")
    void syncTickets_shouldReturnBusinessErrorStatus() throws Exception {
        when(syncService.syncTickets(any(TicketSyncRequest.class)))
                .thenThrow(new BusinessException("Usuario autenticado no encontrado", HttpStatus.UNAUTHORIZED, "USER_NOT_FOUND"));
        var req = new TicketSyncRequest("tablet-1", List.of(sale("c-1")));

        mvc.perform(post("/api/v1/sync/tickets").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(req)))
                .andExpect(status().isUnauthorized());
    }

    // ---------- POST /sync/boardings ----------

    @ParameterizedTest
    @ValueSource(strings = {"DRIVER", "DISPATCHER"})
    void syncBoardings_shouldReturn200ForAllowedRoles(String role) throws Exception {
        when(syncService.syncBoardings(any(BoardingSyncRequest.class))).thenReturn(new SyncBatchResponse(
                101L, 1, 0, 1, 0, List.of(new SyncItemResult(null, SyncItemResult.Status.DUPLICATE, 5L, "QR-5", null, null))));
        var req = new BoardingSyncRequest("phone-1", List.of(new OfflineBoarding("QR-5", LocalDateTime.now())));

        mvc.perform(post("/api/v1/sync/boardings").with(user("u").roles(role)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicates").value(1))
                .andExpect(jsonPath("$.results[0].qrCode").value("QR-5"))
                .andExpect(jsonPath("$.results[0].offlineClientId").doesNotExist());
    }

    @ParameterizedTest
    @ValueSource(strings = {"CLERK", "PASSENGER", "ADMIN"})
    void syncBoardings_shouldReturn403ForOtherRoles(String role) throws Exception {
        var req = new BoardingSyncRequest("phone-1", List.of(new OfflineBoarding("QR-5", LocalDateTime.now())));

        mvc.perform(post("/api/v1/sync/boardings").with(user("u").roles(role)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(req)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(syncService);
    }

    @Test
    void syncBoardings_shouldReturn401WithoutAuthentication() throws Exception {
        var req = new BoardingSyncRequest("phone-1", List.of(new OfflineBoarding("QR-5", LocalDateTime.now())));

        mvc.perform(post("/api/v1/sync/boardings").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(req)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "DRIVER")
    void syncBoardings_shouldReturn400WhenBoardingIncomplete() throws Exception {
        var req = Map.of("deviceId", "phone-1", "boardings", List.of(Map.of("qrCode", "QR-5")));

        mvc.perform(post("/api/v1/sync/boardings").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(syncService);
    }

    // ---------- GET /sync/conflicts ----------

    @ParameterizedTest
    @ValueSource(strings = {"CLERK", "DRIVER", "DISPATCHER", "ADMIN"})
    void getConflicts_shouldReturn200ForAllowedRoles(String role) throws Exception {
        when(syncService.getConflicts("tablet-1", false)).thenReturn(List.of(conflictResponse(null)));

        mvc.perform(get("/api/v1/sync/conflicts").param("deviceId", "tablet-1").with(user("u").roles(role)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].offlineClientId").value("c-2"))
                .andExpect(jsonPath("$[0].code").value("SEAT_NOT_AVAILABLE"))
                .andExpect(jsonPath("$[0].type").value("TICKETS"));
    }

    @Test
    @WithMockUser(roles = "CLERK")
    void getConflicts_shouldAllowMissingDeviceId_andReturnOnlyOpenByDefault() throws Exception {
        when(syncService.getConflicts(null, false)).thenReturn(List.of());

        mvc.perform(get("/api/v1/sync/conflicts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
        verify(syncService).getConflicts(null, false);
    }

    @Test
    @WithMockUser(roles = "DISPATCHER")
    void getConflicts_shouldPassResolvedFilter() throws Exception {
        when(syncService.getConflicts(null, true)).thenReturn(List.of(conflictResponse(LocalDateTime.now())));

        mvc.perform(get("/api/v1/sync/conflicts").param("resolved", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].resolvedAt").isNotEmpty())
                .andExpect(jsonPath("$[0].resolvedById").value(20));
    }

    @Test
    @WithMockUser(roles = "PASSENGER")
    void getConflicts_shouldReturn403ForPassenger() throws Exception {
        mvc.perform(get("/api/v1/sync/conflicts"))
                .andExpect(status().isForbidden());
    }

    @Test
    void getConflicts_shouldReturn401WithoutAuthentication() throws Exception {
        mvc.perform(get("/api/v1/sync/conflicts"))
                .andExpect(status().isUnauthorized());
    }

    // ---------- PATCH /sync/conflicts/{id}/resolve ----------

    @ParameterizedTest
    @ValueSource(strings = {"CLERK", "DISPATCHER", "ADMIN"})
    void resolveConflict_shouldReturn200ForAllowedRoles(String role) throws Exception {
        when(syncService.resolveConflict(1L)).thenReturn(conflictResponse(LocalDateTime.now()));

        mvc.perform(patch("/api/v1/sync/conflicts/1/resolve").with(user("u").roles(role)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.resolvedAt").isNotEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRIVER", "PASSENGER"})
    void resolveConflict_shouldReturn403ForOtherRoles(String role) throws Exception {
        mvc.perform(patch("/api/v1/sync/conflicts/1/resolve").with(user("u").roles(role)).with(csrf()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(syncService);
    }

    @Test
    @WithMockUser(roles = "CLERK")
    void resolveConflict_shouldReturn422WhenAlreadyResolved() throws Exception {
        when(syncService.resolveConflict(1L)).thenThrow(new InvalidStateTransitionException("El conflicto ya fue resuelto"));

        mvc.perform(patch("/api/v1/sync/conflicts/1/resolve").with(csrf()))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @WithMockUser(roles = "CLERK")
    void resolveConflict_shouldReturn403WhenConflictBelongsToOtherClerk() throws Exception {
        when(syncService.resolveConflict(1L)).thenThrow(new BusinessException("El conflicto pertenece a otro usuario",
                HttpStatus.FORBIDDEN, "NOT_CONFLICT_OWNER"));

        mvc.perform(patch("/api/v1/sync/conflicts/1/resolve").with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void resolveConflict_shouldReturn404WhenUnknown() throws Exception {
        when(syncService.resolveConflict(99L)).thenThrow(new ResourceNotFoundException("Conflicto de sincronización", 99L));

        mvc.perform(patch("/api/v1/sync/conflicts/99/resolve").with(csrf()))
                .andExpect(status().isNotFound());
    }

    private SyncConflictResponse conflictResponse(LocalDateTime resolvedAt) {
        return new SyncConflictResponse(1L, 100L, "tablet-1", SyncBatch.SyncType.TICKETS, "c-2",
                "SEAT_NOT_AVAILABLE", "ocupado", "{}", LocalDateTime.now(), resolvedAt, resolvedAt == null ? null : 20L);
    }
}
