package com.web.dto.payment;

import com.web.entity.Ticket;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

// Comprobante digital del pago de un ticket
public record PaymentResponse(
    Long paymentId,
    String receiptNumber,  // RCP-<id del pago>
    Long ticketId,
    String qrCode,
    Ticket.PaymentMethod paymentMethod,
    BigDecimal amount,
    Ticket.PaymentStatus paymentStatus,
    String transactionReference,
    String proofImageUrl,
    LocalDateTime paidAt,
    String confirmedBy  // Nombre de quien recibió el pago
) implements Serializable {}
