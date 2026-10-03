package com.web.dto.dispatch.Assignment.mapper;

import com.web.dto.dispatch.Assignment.AssignmentCreateRequest;
import com.web.dto.dispatch.Assignment.AssignmentResponse;
import com.web.dto.dispatch.Assignment.AssignmentUpdateRequest;
import com.web.entity.Assignment;
import org.mapstruct.*;

import java.util.List;

@Mapper(componentModel = "spring")
public interface AssignmentMapper {
    
    // Entity → Response con info simple de conductor
    @Mapping(target = "tripId", source = "trip.id")
    @Mapping(target = "driverId", source = "driver.id")
    @Mapping(target = "driverName", source = "driver.name")
    @Mapping(target = "driverPhone", source = "driver.phone")
    @Mapping(target = "dispatcherId", source = "dispatcher.id")
    @Mapping(target = "dispatcherName", source = "dispatcher.name")
    @Mapping(target = "routeName", source = "trip.route.name")
    @Mapping(target = "tripDate", source = "trip.tripDate")
    @Mapping(target = "departureTime", source = "trip.departureTime")
    @Mapping(target = "arrivalEta", source = "trip.arrivalEta")
    @Mapping(target = "busPlate", source = "trip.bus.plate")
    @Mapping(target = "soatExpiresAt", source = "trip.bus.soatExpiresAt")
    @Mapping(target = "technicalReviewExpiresAt", source = "trip.bus.technicalReviewExpiresAt")
    @Mapping(target = "soatValidOnTripDate", expression = "java(assignment.soatValidOnTripDate())")
    @Mapping(target = "reviewValidOnTripDate", expression = "java(assignment.reviewValidOnTripDate())")
    AssignmentResponse toResponse(Assignment assignment);
    
    List<AssignmentResponse> toResponseList(List<Assignment> assignments);
    
    // Request → Entity (sin relaciones)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "trip", ignore = true)
    @Mapping(target = "driver", ignore = true)
    @Mapping(target = "dispatcher", ignore = true)
    @Mapping(target = "checklistOk", constant = "false")
    @Mapping(target = "assignedAt", ignore = true)
    Assignment toEntity(AssignmentCreateRequest request);
    
    // Update parcial
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "trip", ignore = true)
    @Mapping(target = "driver", ignore = true)
    @Mapping(target = "dispatcher", ignore = true)
    @Mapping(target = "assignedAt", ignore = true)
    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    void updateEntityFromRequest(AssignmentUpdateRequest request, @MappingTarget Assignment assignment);
}

