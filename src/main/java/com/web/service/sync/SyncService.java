package com.web.service.sync;

import com.web.dto.sync.BoardingSyncRequest;
import com.web.dto.sync.SyncBatchResponse;
import com.web.dto.sync.SyncConflictResponse;
import com.web.dto.sync.TicketSyncRequest;

import java.util.List;

public interface SyncService {

    SyncBatchResponse syncTickets(TicketSyncRequest request);

    SyncBatchResponse syncBoardings(BoardingSyncRequest request);

    List<SyncConflictResponse> getConflicts(String deviceId);
}
