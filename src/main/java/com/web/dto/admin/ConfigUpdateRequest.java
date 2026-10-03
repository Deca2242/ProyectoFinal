package com.web.dto.admin;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Map;

// Actualización parcial: los campos null no se modifican.
// La política de reembolso debe quedar monótona (48h >= 24h >= 12h >= 6h >= <6h): lo valida el servicio
// combinando los valores enviados con los vigentes (400 REFUND_POLICY_NOT_MONOTONIC)
public record ConfigUpdateRequest(
        @Min(1) @Max(120) Integer holdDurationMinutes,
        @Min(0) @Max(100) Integer noShowFeePercentage,
        @Min(0) @Max(100) Integer overbookingPercentage,
        Map<String, @NotNull @Min(0) @Max(100) Integer> discountPercentages,
        @DecimalMin("0.0") BigDecimal baggageWeightLimit,
        @DecimalMin(value = "0.0", inclusive = false) BigDecimal baggagePricePerKg,
        // Configuraciones adicionales
        @DecimalMin("0.0") BigDecimal noShowFee,
        @DecimalMin("0.0") @DecimalMax("1.0") Double overbookingMaxPercentage,
        // Políticas de Reembolso (porcentajes 0-100)
        @DecimalMin("0") @DecimalMax("100") BigDecimal refundPercentage48Hours,
        @DecimalMin("0") @DecimalMax("100") BigDecimal refundPercentage24Hours,
        @DecimalMin("0") @DecimalMax("100") BigDecimal refundPercentage12Hours,
        @DecimalMin("0") @DecimalMax("100") BigDecimal refundPercentage6Hours,
        @DecimalMin("0") @DecimalMax("100") BigDecimal refundPercentageLess6Hours,
        // Precios de Tickets: el precio base debe ser positivo y un multiplicador nunca abarata el pasaje
        @DecimalMin(value = "0.0", inclusive = false) BigDecimal ticketBasePrice,
        @DecimalMin("1.0") BigDecimal ticketPriceMultiplierPeakHours,
        @DecimalMin("1.0") BigDecimal ticketPriceMultiplierHighDemand,
        @DecimalMin("1.0") BigDecimal ticketPriceMultiplierMediumDemand,
        // Límites operativos
        @DecimalMin(value = "0.0", inclusive = false) Double baggageWeightMax,
        @Min(1) @Max(10) Integer parcelOtpMaxAttempts,
        @Min(1) @Max(50) Integer maxActiveHoldsPerUserAndTrip,
        @Min(0) @Max(120) Integer noShowWindowMinutes,
        @DecimalMin("0.0") @DecimalMax("1.0") Double overbookingMinOccupancy,
        @Min(0) @Max(1440) Integer overbookingWindowMinutes,
        Boolean dynamicPricingDefault) implements Serializable {
}
