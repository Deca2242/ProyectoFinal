package com.web.service.incident;

import com.web.dto.incident.IncidentCreateRequest;
import com.web.dto.incident.IncidentResponse;
import com.web.entity.Incident;

import java.time.LocalDate;
import java.util.List;

public interface IncidentService {

    IncidentResponse reportIncident(IncidentCreateRequest request);

    // Filtros opcionales; from/to son fechas inclusivas sobre createdAt
    List<IncidentResponse> searchIncidents(Incident.IncidentType type, Incident.EntityType entityType,
                                           Long entityId, LocalDate from, LocalDate to);
}
