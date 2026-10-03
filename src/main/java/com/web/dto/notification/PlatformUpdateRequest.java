package com.web.dto.notification;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.io.Serializable;

// Andén de salida asignado por el despachador (p. ej. "A3")
public record PlatformUpdateRequest(
        @NotBlank @Size(max = 20) String platform
) implements Serializable {
}
