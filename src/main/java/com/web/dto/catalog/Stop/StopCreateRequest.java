package com.web.dto.catalog.Stop;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.io.Serializable;
import java.math.BigDecimal;

// La ruta sale del path; routeId es opcional y, si se envía, debe coincidir con él.
// El orden debe ser el siguiente al de la última parada (orden contiguo desde 1)
public record StopCreateRequest(
    Long routeId,
    @NotBlank String name,
    @NotNull @Min(1) Integer order,
    @DecimalMin("-90") @DecimalMax("90") BigDecimal latitude,
    @DecimalMin("-180") @DecimalMax("180") BigDecimal longitude
) implements Serializable {}
