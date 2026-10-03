package com.web.service.baggage;

import com.web.dto.baggage.BaggageCreateRequest;
import com.web.dto.baggage.BaggageResponse;
import com.web.entity.Baggage;
import com.web.entity.Ticket;

import java.math.BigDecimal;

// Equipaje de un ticket: límite de peso, cobro por exceso (tarifas desde Config), etiqueta y maletero
public interface BaggageService {

    // Registra el equipaje de un ticket ya guardado (compra o registro posterior en taquilla).
    // Si no se pidió equipaje (request null) no hace nada y devuelve null
    Baggage registerForTicket(Ticket ticket, BaggageCreateRequest request);

    BaggageResponse addBaggage(Long ticketId, BaggageCreateRequest request);

    BaggageResponse getBaggage(Long ticketId);

    // Solo antes de que el pasajero aborde
    void removeBaggage(Long ticketId);

    // Cargo por los kg que superan "baggage.weight.limit" a la tarifa "baggage.price.per.kg"
    BigDecimal calculateExcessFee(BigDecimal weightKg);
}
