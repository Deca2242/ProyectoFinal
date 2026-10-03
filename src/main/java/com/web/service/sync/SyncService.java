package com.web.service.sync;

import com.web.dto.sync.BoardingSyncRequest;
import com.web.dto.sync.SyncBatchResponse;
import com.web.dto.sync.SyncConflictResponse;
import com.web.dto.sync.TicketSyncRequest;

import java.util.List;

public interface SyncService {

    SyncBatchResponse syncTickets(TicketSyncRequest request);

    SyncBatchResponse syncBoardings(BoardingSyncRequest request);

    // Abiertos (resolved = false) o ya revisados (resolved = true)
    List<SyncConflictResponse> getConflicts(String deviceId, boolean resolved);

    // Marca un conflicto como revisado por el usuario autenticado
    SyncConflictResponse resolveConflict(Long conflictId);
}
