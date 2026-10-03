package com.web.dto.sync;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.io.Serializable;

// Resultado de una operación del lote (offlineClientId en ventas, qrCode en abordajes)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SyncItemResult(
        String offlineClientId,
        Status status,
        Long ticketId,
        String qrCode,
        String code,
        String message
) implements Serializable {

    public enum Status {
        SYNCED,
        DUPLICATE,
        CONFLICT
    }
}
