package com.web.dto.dispatch.Assignment;

import java.io.Serializable;

// Todos los campos son opcionales; busId cambia el bus del viaje (mismas validaciones que la reprogramación)
public record AssignmentUpdateRequest(
    Long driverId,
    Boolean checklistOk,
    Boolean soatValid,
    Boolean revisionValid,
    Long busId
) implements Serializable {

    // Actualización sin cambio de bus
    public AssignmentUpdateRequest(Long driverId, Boolean checklistOk, Boolean soatValid, Boolean revisionValid) {
        this(driverId, checklistOk, soatValid, revisionValid, null);
    }
}
