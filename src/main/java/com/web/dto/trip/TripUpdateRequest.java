package com.web.dto.trip;

import java.io.Serializable;
import java.time.LocalDateTime;

// Reprogramación de un viaje SCHEDULED (campos nulos no se modifican). El estado no se cambia por aquí:
// tiene sus propios endpoints con sus validaciones
public record TripUpdateRequest(
    LocalDateTime departureTime,
    LocalDateTime arrivalEta,
    Long busId
) implements Serializable {}
