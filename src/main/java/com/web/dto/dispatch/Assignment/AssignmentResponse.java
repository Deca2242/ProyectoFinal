package com.web.dto.dispatch.Assignment;

import java.io.Serializable;
import java.time.LocalDateTime;

public record AssignmentResponse(
    Long id,
    Long tripId,
    Long driverId,
    String driverName,  // Info simple
    String driverPhone,
    Long dispatcherId,
    String dispatcherName,
    Boolean checklistOk,
    Boolean soatValid,
    Boolean revisionValid,
    LocalDateTime assignedAt,
    // Datos del viaje para que el conductor vea su programación (GET /assignments/me)
    String routeName,
    java.time.LocalDate tripDate,
    LocalDateTime departureTime,
    LocalDateTime arrivalEta,
    String busPlate
) implements Serializable {}

