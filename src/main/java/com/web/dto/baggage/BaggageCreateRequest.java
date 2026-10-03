package com.web.dto.baggage;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.io.Serializable;
import java.math.BigDecimal;

public record BaggageCreateRequest(
    @NotNull @jakarta.validation.constraints.Positive @jakarta.validation.constraints.DecimalMax("999.99") BigDecimal weightKg,
    BigDecimal excessFee,  // Calculado por servicio
    @Size(max = 20) @Pattern(regexp = "^[A-Za-z0-9_-]+$") String compartment  // Opcional: maletero, por defecto MAIN
) implements Serializable {

    // Equipaje en el maletero principal
    public BaggageCreateRequest(BigDecimal weightKg, BigDecimal excessFee) {
        this(weightKg, excessFee, null);
    }
}
