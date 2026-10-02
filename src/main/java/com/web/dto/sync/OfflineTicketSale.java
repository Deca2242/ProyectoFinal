package com.web.dto.sync;

import com.web.entity.Ticket;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

// Venta registrada en el dispositivo sin conexión (pendingSync)
public record OfflineTicketSale(
        @NotBlank @Size(max = 64) String offlineClientId,
        @NotNull Long tripId,
        @NotNull Long passengerId,
        @NotNull @Positive Integer seatNumber,
        @NotNull Long fromStopId,
        @NotNull Long toStopId,
        @NotNull Ticket.PaymentMethod paymentMethod,
        String passengerType,
        @NotNull LocalDateTime soldAt,
        @Positive @DecimalMax("999.99") BigDecimal baggageWeightKg // Opcional
) implements Serializable {
}
