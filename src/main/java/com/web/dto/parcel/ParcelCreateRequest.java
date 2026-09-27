package com.web.dto.parcel;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.io.Serializable;
import java.math.BigDecimal;

public record ParcelCreateRequest(
    @NotNull Long tripId,
    @NotBlank String senderName,
    @NotBlank String senderPhone,
    @NotBlank String receiverName,
    @NotBlank String receiverPhone,
    @NotNull Long fromStopId,
    String fromStopName,  // Info mínima
    @NotNull Long toStopId,
    String toStopName,
    @NotNull BigDecimal price,
    @jakarta.validation.constraints.Positive BigDecimal weightKg,
    String description
) implements Serializable {}

