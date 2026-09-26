package com.web.dto.ticket;

import com.web.dto.baggage.BaggageResponse;
import com.web.entity.Ticket;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

// Información mínima de tramos
public record TicketResponse(
    Long id,
    Long tripId,
    String routeName,  // Info básica del trip
    LocalDate tripDate,
    LocalDateTime departureTime,
    Long passengerId,
    String passengerName,
    String passengerEmail,
    Integer seatNumber,
    Long fromStopId,
    String fromStopName,  // Mínimo
    Integer fromStopOrder,
    Long toStopId,
    String toStopName,
    Integer toStopOrder,
    BigDecimal price,
    Ticket.PaymentMethod paymentMethod,
    Ticket.TicketStatus status,
    String qrCode,
    LocalDateTime purchasedAt,
    BaggageResponse baggage,  // Puede ser null
    LocalDateTime boardedAt   // null si aún no aborda
) implements Serializable {}

