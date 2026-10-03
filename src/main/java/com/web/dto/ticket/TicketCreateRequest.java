package com.web.dto.ticket;

import com.web.dto.baggage.BaggageCreateRequest;
import com.web.entity.Ticket;
import jakarta.validation.constraints.NotNull;

import java.io.Serializable;
import java.math.BigDecimal;

// Información mínima de tramos
public record TicketCreateRequest(
        @NotNull Long tripId,
        @NotNull Long passengerId,
        @NotNull Integer seatNumber,
        @NotNull Long fromStopId,
        String fromStopName, // Info mínima para validación
        Integer fromStopOrder,
        @NotNull Long toStopId,
        String toStopName,
        Integer toStopOrder,
        @NotNull BigDecimal price,
        @NotNull Ticket.PaymentMethod paymentMethod,
        BaggageCreateRequest baggage, // Opcional
        String passengerType // Opcional: "STUDENT", "SENIOR", "CHILD", "ADULT" (default)
) implements Serializable {
}
