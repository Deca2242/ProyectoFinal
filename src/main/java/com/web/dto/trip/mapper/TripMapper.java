package com.web.dto.trip.mapper;

import com.web.dto.catalog.Bus.mapper.BusMapper;
import com.web.dto.catalog.Route.mapper.RouteMapper;
import com.web.dto.dispatch.Assignment.mapper.AssignmentMapper;
import com.web.dto.trip.TripCreateRequest;
import com.web.dto.trip.TripDetailResponse;
import com.web.dto.trip.TripResponse;
import com.web.dto.trip.TripUpdateRequest;
import com.web.entity.Trip;
import org.mapstruct.*;

import java.util.List;

@Mapper(componentModel = "spring", uses = {RouteMapper.class, BusMapper.class, AssignmentMapper.class})
public interface TripMapper {
    
    // Entity → Response simple
    @Mapping(target = "routeId", source = "route.id")
    @Mapping(target = "routeName", source = "route.name")
    @Mapping(target = "routeOrigin", source = "route.origin")
    @Mapping(target = "routeDestination", source = "route.destination")
    @Mapping(target = "busId", source = "bus.id")
    @Mapping(target = "busPlate", source = "bus.plate")
    @Mapping(target = "busCapacity", source = "bus.capacity")
    @Mapping(target = "soldSeats", ignore = true) // Se calcula en servicio
    @Mapping(target = "occupancyPercentage", ignore = true) // Se calcula en servicio
    TripResponse toResponse(Trip trip);
    
    List<TripResponse> toResponseList(List<Trip> trips);
    
    // Entity → Response detallado
    @Mapping(target = "route", source = "route")
    @Mapping(target = "bus", source = "bus")
    @Mapping(target = "assignment", source = "assignment")
    @Mapping(target = "soldSeats", ignore = true) // Se calcula en servicio
    @Mapping(target = "availableSeats", ignore = true) // Se calcula en servicio
    @Mapping(target = "occupancyPercentage", ignore = true) // Se calcula en servicio
    @Mapping(target = "availableSeatNumbers", ignore = true) // Se calcula en servicio
    TripDetailResponse toDetailResponse(Trip trip);
    
    // Request → Entity
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "route", ignore = true)
    @Mapping(target = "bus", ignore = true)
    @Mapping(target = "status", constant = "SCHEDULED")
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "seatHolds", ignore = true)
    @Mapping(target = "tickets", ignore = true)
    @Mapping(target = "parcels", ignore = true)
    @Mapping(target = "departedAt", ignore = true)
    @Mapping(target = "arrivedAt", ignore = true)
    @Mapping(target = "overbookingApprovedSeats", ignore = true)
    @Mapping(target = "platform", ignore = true)
    @Mapping(target = "arrivalNotified", ignore = true)
    @Mapping(target = "assignment", ignore = true)
    Trip toEntity(TripCreateRequest request);
    
    // Update parcial
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "route", ignore = true)
    @Mapping(target = "bus", ignore = true)
    @Mapping(target = "tripDate", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "seatHolds", ignore = true)
    @Mapping(target = "tickets", ignore = true)
    @Mapping(target = "parcels", ignore = true)
    @Mapping(target = "assignment", ignore = true)
    @Mapping(target = "departedAt", ignore = true)
    @Mapping(target = "arrivedAt", ignore = true)
    @Mapping(target = "overbookingApprovedSeats", ignore = true)
    @Mapping(target = "platform", ignore = true)
    @Mapping(target = "arrivalNotified", ignore = true)
    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    void updateEntityFromRequest(TripUpdateRequest request, @MappingTarget Trip trip);
}

