package com.web.dto.incident;

import com.web.entity.Incident;

import java.io.Serializable;
import java.time.LocalDateTime;

public record IncidentResponse(
    Long id,
    Incident.IncidentType type,
    Incident.EntityType entityType,
    Long entityId,
    String description,
    Long reportedById,
    String reportedByName,
    LocalDateTime createdAt
) implements Serializable {}
