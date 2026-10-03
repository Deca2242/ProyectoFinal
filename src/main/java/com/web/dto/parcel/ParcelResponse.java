package com.web.dto.parcel;

import com.web.entity.Parcel;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record ParcelResponse(
    Long id,
    String code,
    Long tripId,
    String routeName,  // Info básica
    LocalDate tripDate,
    String senderName,
    String senderPhone,
    String receiverName,
    String receiverPhone,
    Long fromStopId,
    String fromStopName,  // Mínimo
    Long toStopId,
    String toStopName,
    BigDecimal price,
    BigDecimal weightKg,
    Parcel.ParcelStatus status,
    String deliveryOtp,  // Solo si status permite
    String proofPhotoUrl,
    LocalDateTime createdAt,
    LocalDateTime deliveredAt,
    String description,
    Integer otpAttempts  // Intentos de OTP fallidos hasta ahora
) implements Serializable {}

