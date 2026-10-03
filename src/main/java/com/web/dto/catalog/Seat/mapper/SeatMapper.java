package com.web.dto.catalog.Seat.mapper;

import com.web.dto.catalog.Seat.SeatResponse;
import com.web.entity.Seat;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper(componentModel = "spring")
public interface SeatMapper {

    // Entity → Response
    @Mapping(target = "busId", source = "bus.id")
    SeatResponse toResponse(Seat seat);

    List<SeatResponse> toResponseList(List<Seat> seats);
}
