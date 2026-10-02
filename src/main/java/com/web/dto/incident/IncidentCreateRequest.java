package com.web.dto.incident;

import com.web.entity.Incident;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.io.Serializable;

public record IncidentCreateRequest(
    @NotNull Incident.IncidentType type,
    @NotNull Incident.EntityType entityType,
    @NotNull Long entityId,
    @NotBlank @Size(max = 2000) String description
) implements Serializable {}
