package com.web.dto.catalog.Stop;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Pattern;

import java.io.Serializable;
import java.math.BigDecimal;

// Actualización parcial de una parada (los campos nulos no se modifican). El orden no se cambia
// por aquí: alteraría los tramos de los tickets ya vendidos
public record StopUpdateRequest(
    @Pattern(regexp = ".*\\S.*", message = "no puede estar vacío") String name,
    @DecimalMin("-90") @DecimalMax("90") BigDecimal latitude,
    @DecimalMin("-180") @DecimalMax("180") BigDecimal longitude
) implements Serializable {}
