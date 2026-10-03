package com.web.dto.sync;

import java.io.Serializable;
import java.util.List;

public record SyncBatchResponse(
        Long batchId,
        int total,
        int synced,
        int duplicates,
        int conflicts,
        List<SyncItemResult> results
) implements Serializable {
}
