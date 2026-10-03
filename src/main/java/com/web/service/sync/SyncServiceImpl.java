package com.web.service.sync;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.dto.sync.BoardingSyncRequest;
import com.web.dto.sync.OfflineBoarding;
import com.web.dto.sync.OfflineTicketSale;
import com.web.dto.sync.SyncBatchResponse;
import com.web.dto.sync.SyncConflictResponse;
import com.web.dto.sync.SyncItemResult;
import com.web.dto.sync.TicketSyncRequest;
import com.web.dto.sync.mapper.SyncMapper;
import com.web.entity.SyncBatch;
import com.web.entity.SyncConflict;
import com.web.entity.Ticket;
import com.web.entity.User;
import com.web.exception.BusinessException;
import com.web.exception.InvalidStateTransitionException;
import com.web.exception.ResourceNotFoundException;
import com.web.repository.SyncBatchRepository;
import com.web.repository.SyncConflictRepository;
import com.web.repository.TicketRepository;
import com.web.repository.UserRepository;
import com.web.service.ticket.OfflineSaleContext;
import com.web.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

// Sincronización de las operaciones hechas sin conexión (taquilla y conductor).
// Sin transacción propia: cada operación se confirma por separado en OfflineSyncProcessor
@Slf4j
@Service
@RequiredArgsConstructor
public class SyncServiceImpl implements SyncService {

    private final OfflineSyncProcessor processor;
    private final TicketRepository ticketRepository;
    private final UserRepository userRepository;
    private final SyncBatchRepository syncBatchRepository;
    private final SyncConflictRepository syncConflictRepository;
    private final SyncMapper syncMapper;
    private final ObjectMapper objectMapper;

    @Override
    public SyncBatchResponse syncTickets(TicketSyncRequest request) {
        validateBatchSize(request.sales().size());
        User user = currentUser();
        LocalDateTime now = LocalDateTime.now();

        SyncBatch batch = newBatch(request.deviceId(), user, SyncBatch.SyncType.TICKETS, now);
        List<SyncItemResult> results = new ArrayList<>();
        for (OfflineTicketSale sale : request.sales()) {
            SyncItemResult result = syncSale(sale, now);
            results.add(result);
            if (result.status() == SyncItemResult.Status.CONFLICT) {
                batch.addConflict(conflictOf(sale.offlineClientId(), result, sale));
            }
        }
        return saveBatch(batch, results);
    }

    @Override
    public SyncBatchResponse syncBoardings(BoardingSyncRequest request) {
        validateBatchSize(request.boardings().size());
        User user = currentUser();

        SyncBatch batch = newBatch(request.deviceId(), user, SyncBatch.SyncType.BOARDINGS, LocalDateTime.now());
        List<SyncItemResult> results = new ArrayList<>();
        for (OfflineBoarding boarding : request.boardings()) {
            SyncItemResult result = syncBoarding(boarding);
            results.add(result);
            if (result.status() == SyncItemResult.Status.CONFLICT) {
                batch.addConflict(conflictOf(boarding.qrCode(), result, boarding));
            }
        }
        return saveBatch(batch, results);
    }

    // Conflictos del usuario autenticado; ADMIN ve los de todos
    @Override
    @Transactional(readOnly = true)
    public List<SyncConflictResponse> getConflicts(String deviceId, boolean resolved) {
        String device = deviceId != null && !deviceId.isBlank() ? deviceId : null;
        Long userId = SecurityUtils.hasRole("ADMIN") ? null : currentUser().getId();
        return syncMapper.toConflictResponseList(syncConflictRepository.search(userId, device, resolved));
    }

    // ADMIN y DISPATCHER revisan cualquier conflicto; la taquilla solo los de sus propios lotes.
    // Resolver un conflicto ya resuelto es una transición inválida (422)
    @Override
    @Transactional
    public SyncConflictResponse resolveConflict(Long conflictId) {
        SyncConflict conflict = syncConflictRepository.findById(conflictId)
                .orElseThrow(() -> new ResourceNotFoundException("Conflicto de sincronización", conflictId));
        User user = currentUser();

        boolean supervisor = SecurityUtils.hasRole("ADMIN") || SecurityUtils.hasRole("DISPATCHER");
        if (!supervisor && !user.getId().equals(conflict.getBatch().getUser().getId())) {
            throw new BusinessException("El conflicto pertenece a otro usuario",
                    HttpStatus.FORBIDDEN, "NOT_CONFLICT_OWNER");
        }
        if (conflict.getResolvedAt() != null) {
            throw new InvalidStateTransitionException("El conflicto ya fue resuelto el " + conflict.getResolvedAt());
        }

        conflict.setResolvedAt(LocalDateTime.now());
        conflict.setResolvedBy(user);
        return syncMapper.toConflictResponse(syncConflictRepository.save(conflict));
    }

    private SyncItemResult syncSale(OfflineTicketSale sale, LocalDateTime now) {
        String clientId = sale.offlineClientId();
        if (sale.soldAt().isAfter(now.plusMinutes(OfflineSaleContext.CLOCK_TOLERANCE_MINUTES))) {
            return conflict(clientId, null, "INVALID_SOLD_AT", "La hora de la venta offline no puede estar en el futuro");
        }
        try {
            return processor.syncSale(sale);
        } catch (BusinessException ex) {
            return duplicateIfAlreadySynced(clientId)
                    .orElseGet(() -> conflict(clientId, null, ex.getCode(), ex.getMessage()));
        } catch (DataIntegrityViolationException ex) {
            return duplicateIfAlreadySynced(clientId)
                    .orElseGet(() -> conflict(clientId, null, "DATA_INTEGRITY_VIOLATION",
                            "La venta viola una restricción de datos"));
        } catch (RuntimeException ex) {
            log.error("Error inesperado al sincronizar la venta offline {}", clientId, ex);
            return conflict(clientId, null, "SYNC_ERROR", "Error inesperado al sincronizar la venta");
        }
    }

    // Si el mismo lote se envió dos veces a la vez, el otro envío pudo vender la silla primero:
    // la venta ya está registrada con este id, así que es un reenvío y no un conflicto
    private Optional<SyncItemResult> duplicateIfAlreadySynced(String clientId) {
        return ticketRepository.findByOfflineClientId(clientId)
                .map(t -> new SyncItemResult(clientId, SyncItemResult.Status.DUPLICATE, t.getId(), t.getQrCode(), null, null));
    }

    private SyncItemResult syncBoarding(OfflineBoarding boarding) {
        String qrCode = boarding.qrCode();
        try {
            return processor.syncBoarding(boarding);
        } catch (BusinessException ex) {
            if ("TICKET_ALREADY_BOARDED".equals(ex.getCode())) {
                Long ticketId = ticketRepository.findByQrCode(qrCode).map(Ticket::getId).orElse(null);
                return new SyncItemResult(null, SyncItemResult.Status.DUPLICATE, ticketId, qrCode, null, null);
            }
            return conflict(null, qrCode, ex.getCode(), ex.getMessage());
        } catch (DataIntegrityViolationException ex) {
            return conflict(null, qrCode, "DATA_INTEGRITY_VIOLATION", "El abordaje viola una restricción de datos");
        } catch (RuntimeException ex) {
            log.error("Error inesperado al sincronizar el abordaje {}", qrCode, ex);
            return conflict(null, qrCode, "SYNC_ERROR", "Error inesperado al sincronizar el abordaje");
        }
    }

    private void validateBatchSize(int size) {
        if (size > TicketSyncRequest.MAX_BATCH_SIZE) {
            throw new BusinessException("El lote supera el máximo de " + TicketSyncRequest.MAX_BATCH_SIZE + " operaciones",
                    HttpStatus.BAD_REQUEST, "SYNC_BATCH_TOO_LARGE");
        }
    }

    private User currentUser() {
        return SecurityUtils.currentUsername()
                .flatMap(userRepository::findByEmail)
                .orElseThrow(() -> new BusinessException("Usuario autenticado no encontrado",
                        HttpStatus.UNAUTHORIZED, "USER_NOT_FOUND"));
    }

    private SyncBatch newBatch(String deviceId, User user, SyncBatch.SyncType type, LocalDateTime receivedAt) {
        return SyncBatch.builder()
                .deviceId(deviceId)
                .user(user)
                .type(type)
                .receivedAt(receivedAt)
                .build();
    }

    // Guarda el lote con sus conflictos (cascada) y arma la respuesta
    private SyncBatchResponse saveBatch(SyncBatch batch, List<SyncItemResult> results) {
        int synced = count(results, SyncItemResult.Status.SYNCED);
        int duplicates = count(results, SyncItemResult.Status.DUPLICATE);
        int conflicts = count(results, SyncItemResult.Status.CONFLICT);
        batch.setTotal(results.size());
        batch.setSynced(synced);
        batch.setDuplicates(duplicates);
        batch.setConflicts(conflicts);
        SyncBatch saved = syncBatchRepository.save(batch);
        return new SyncBatchResponse(saved.getId(), results.size(), synced, duplicates, conflicts, results);
    }

    private static int count(List<SyncItemResult> results, SyncItemResult.Status status) {
        return (int) results.stream().filter(r -> r.status() == status).count();
    }

    private static SyncItemResult conflict(String offlineClientId, String qrCode, String code, String message) {
        return new SyncItemResult(offlineClientId, SyncItemResult.Status.CONFLICT, null, qrCode, code, message);
    }

    private SyncConflict conflictOf(String reference, SyncItemResult result, Object payload) {
        return SyncConflict.builder()
                .offlineClientId(reference)
                .code(result.code())
                .reason(result.message())
                .payload(toJson(payload))
                .build();
    }

    private String toJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            return String.valueOf(payload);
        }
    }
}
