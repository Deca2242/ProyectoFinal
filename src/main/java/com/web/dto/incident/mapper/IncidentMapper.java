package com.web.dto.incident.mapper;

import com.web.dto.incident.IncidentCreateRequest;
import com.web.dto.incident.IncidentResponse;
import com.web.entity.Incident;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper(componentModel = "spring")
public interface IncidentMapper {

    // Entity → Response
    @Mapping(target = "type", source = "incidentType")
    @Mapping(target = "reportedById", source = "reportedBy.id")
    @Mapping(target = "reportedByName", source = "reportedBy.name")
    IncidentResponse toResponse(Incident incident);

    List<IncidentResponse> toResponseList(List<Incident> incidents);

    // Request → Entity (quien reporta se asigna en el servicio)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "incidentType", source = "type")
    @Mapping(target = "reportedBy", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    Incident toEntity(IncidentCreateRequest request);
}
