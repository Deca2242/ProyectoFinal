package com.web.dto.baggage;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

// Conteo de equipaje de un viaje para el maletero / panel de despacho
public record TripBaggageSummaryResponse(
    Long tripId,
    Integer totalPieces,
    BigDecimal totalWeightKg,
    BigDecimal totalExcessFee,
    List<BaggageResponse> items
) implements Serializable {}
