package com.web.dto.ticket.reservations.mapper;

import com.web.dto.ticket.reservations.SeatHoldCreateRequest;
import com.web.dto.ticket.reservations.SeatHoldResponse;
import com.web.entity.SeatHold;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper(componentModel = "spring")
public interface SeatHoldMapper {
    
    // Entity → Response
    @Mapping(target = "tripId", source = "trip.id")
    @Mapping(target = "userId", source = "user.id")
    @Mapping(target = "fromStopId", source = "fromStop.id")
    @Mapping(target = "toStopId", source = "toStop.id")
    SeatHoldResponse toResponse(SeatHold seatHold);
    
    List<SeatHoldResponse> toResponseList(List<SeatHold> seatHolds);
    
    // Request → Entity (sin relaciones)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "trip", ignore = true)
    @Mapping(target = "user", ignore = true)
    @Mapping(target = "expiresAt", ignore = true) // Se calcula en servicio
    @Mapping(target = "status", constant = "HOLD")
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "fromStop", ignore = true)
    @Mapping(target = "toStop", ignore = true)
    SeatHold toEntity(SeatHoldCreateRequest request);
}

