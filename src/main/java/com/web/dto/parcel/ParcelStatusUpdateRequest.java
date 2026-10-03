package com.web.dto.parcel;

import com.web.entity.Parcel;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.io.Serializable;

public record ParcelStatusUpdateRequest(
    String code,  // Opcional: si viene debe coincidir con el código de la URL
    @NotNull Parcel.ParcelStatus status,
    @Size(max = 10) String otp,  // Para DELIVERED
    @Size(max = 255) String proofPhotoUrl  // Para DELIVERED
) implements Serializable {}
