package com.web.dto.admin;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Map;

public record ConfigUpdateRequest(
        @Min(1) @Max(120) Integer holdDurationMinutes,
        @Min(0) @Max(100) Integer noShowFeePercentage,
        @Min(0) @Max(100) Integer overbookingPercentage,
        Map<String, @NotNull @Min(0) @Max(100) Integer> discountPercentages,
        @DecimalMin("0.0") BigDecimal baggageWeightLimit,
        @DecimalMin("0.0") BigDecimal baggagePricePerKg,
        // Configuraciones adicionales
        @DecimalMin("0.0") BigDecimal noShowFee,
        @DecimalMin("0.0") @DecimalMax("1.0") Double overbookingMaxPercentage,
        // Políticas de Reembolso (porcentajes 0-100)
        @DecimalMin("0") @DecimalMax("100") BigDecimal refundPercentage48Hours,
        @DecimalMin("0") @DecimalMax("100") BigDecimal refundPercentage24Hours,
        @DecimalMin("0") @DecimalMax("100") BigDecimal refundPercentage12Hours,
        @DecimalMin("0") @DecimalMax("100") BigDecimal refundPercentage6Hours,
        @DecimalMin("0") @DecimalMax("100") BigDecimal refundPercentageLess6Hours,
        // Precios de Tickets
        @DecimalMin("0.0") BigDecimal ticketBasePrice,
        @DecimalMin("0.0") BigDecimal ticketPriceMultiplierPeakHours,
        @DecimalMin("0.0") BigDecimal ticketPriceMultiplierHighDemand,
        @DecimalMin("0.0") BigDecimal ticketPriceMultiplierMediumDemand) implements Serializable {
}
