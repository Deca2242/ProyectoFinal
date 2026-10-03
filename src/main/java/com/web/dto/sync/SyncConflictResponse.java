package com.web.dto.sync;

import com.web.entity.SyncBatch;

import java.io.Serializable;
import java.time.LocalDateTime;

public record SyncConflictResponse(
        Long id,
        Long batchId,
        String deviceId,
        SyncBatch.SyncType type,
        String offlineClientId,
        String code,
        String reason,
        String payload,
        LocalDateTime createdAt,
        // Revisión del conflicto (NULL mientras siga abierto)
        LocalDateTime resolvedAt,
        Long resolvedById
) implements Serializable {
}
