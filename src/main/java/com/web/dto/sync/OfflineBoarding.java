package com.web.dto.sync;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.io.Serializable;
import java.time.LocalDateTime;

// Abordaje validado en el dispositivo sin conexión
public record OfflineBoarding(
        @NotBlank String qrCode,
        @NotNull LocalDateTime boardedAt
) implements Serializable {
}
