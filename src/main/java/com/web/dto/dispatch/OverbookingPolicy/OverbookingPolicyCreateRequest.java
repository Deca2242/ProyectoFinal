package com.web.dto.dispatch.OverbookingPolicy;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.io.Serializable;
import java.math.BigDecimal;

// Franja [startHour, endHour) de la hora de salida y % máximo de overbooking como fracción (0.05 = 5 %).
// Se usa para crear (POST) y para editar (PUT) una política
public record OverbookingPolicyCreateRequest(
    @NotNull @Min(0) @Max(23) Integer startHour,
    @NotNull @Min(1) @Max(24) Integer endHour,
    @NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal maxPercentage
) implements Serializable {

    // La franja no puede estar vacía ni invertida (400 en validationErrors.hourRange)
    @JsonIgnore
    @AssertTrue(message = "La hora de inicio debe ser menor que la hora de fin")
    public boolean isHourRange() {
        return startHour == null || endHour == null || startHour < endHour;
    }
}
