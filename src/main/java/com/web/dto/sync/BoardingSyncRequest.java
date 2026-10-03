package com.web.dto.sync;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.io.Serializable;
import java.util.List;

// Lote de abordajes offline de un dispositivo
public record BoardingSyncRequest(
        @NotBlank @Size(max = 64) String deviceId,
        @NotEmpty @Size(max = TicketSyncRequest.MAX_BATCH_SIZE) List<@Valid @NotNull OfflineBoarding> boardings
) implements Serializable {
}
