package com.web.dto.dispatch;

import java.io.Serializable;

// Resultado de aprobar una silla de overbooking: las sillas aprobadas se venden con números capacity+1..capacity+approved
public record OverbookingApprovalResponse(
    Long tripId,
    Integer capacity,
    Long soldSeats,
    Double occupancyPercentage,
    Integer approvedExtraSeats,
    Integer maxExtraSeats,
    Integer approvedSeatNumber
) implements Serializable {}
