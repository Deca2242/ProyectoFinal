package com.web.dto.payment;

import com.web.entity.Ticket;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.io.Serializable;
import java.math.BigDecimal;

// POST /api/v1/payments/confirm: la taquilla (o el conductor del viaje) confirma el pago de un ticket pendiente
public record PaymentConfirmRequest(
    @NotNull Long ticketId,
    @NotNull Ticket.PaymentMethod paymentMethod,
    @Size(max = 100) String transactionReference,  // Obligatoria para QR/TRANSFER/CARD
    @Positive BigDecimal amount,  // Opcional: si viene debe coincidir con el precio del ticket
    @Size(max = 500) String proofImageUrl  // Opcional
) implements Serializable {}
