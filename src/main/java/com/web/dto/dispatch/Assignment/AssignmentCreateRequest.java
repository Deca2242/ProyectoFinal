package com.web.dto.dispatch.Assignment;

import jakarta.validation.constraints.NotNull;

import java.io.Serializable;

// dispatcherId es opcional: el despachador es el usuario autenticado (si viene y no coincide → 400).
// busId es opcional: cambia el bus del viaje con las mismas validaciones que la reprogramación
public record AssignmentCreateRequest(
    @NotNull Long tripId,
    @NotNull Long driverId,
    Long dispatcherId,
    Long busId
) implements Serializable {

    // Asignación sin cambio de bus
    public AssignmentCreateRequest(Long tripId, Long driverId, Long dispatcherId) {
        this(tripId, driverId, dispatcherId, null);
    }
}
