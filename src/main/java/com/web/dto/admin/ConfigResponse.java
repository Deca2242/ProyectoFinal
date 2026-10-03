package com.web.dto.admin;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

public record ConfigResponse(
    Integer holdDurationMinutes,
    Integer noShowFeePercentage,
    Integer overbookingPercentage,
    Map<String, Integer> discountPercentages,
    BigDecimal baggageWeightLimit,
    BigDecimal baggagePricePerKg,

    // Configuraciones adicionales
    BigDecimal noShowFee,
    Double overbookingMaxPercentage,

    // Políticas de Reembolso
    BigDecimal refundPercentage48Hours,
    BigDecimal refundPercentage24Hours,
    BigDecimal refundPercentage12Hours,
    BigDecimal refundPercentage6Hours,
    BigDecimal refundPercentageLess6Hours,

    // Precios de Tickets
    BigDecimal ticketBasePrice,
    BigDecimal ticketPriceMultiplierPeakHours,
    BigDecimal ticketPriceMultiplierHighDemand,
    BigDecimal ticketPriceMultiplierMediumDemand,

    LocalDateTime lastUpdated,

    // Límites operativos
    Double baggageWeightMax,
    Integer parcelOtpMaxAttempts,
    Integer maxActiveHoldsPerUserAndTrip,
    Integer noShowWindowMinutes,
    Double overbookingMinOccupancy,
    Integer overbookingWindowMinutes,
    Boolean dynamicPricingDefault
) implements Serializable {}
