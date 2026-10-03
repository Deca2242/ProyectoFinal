package com.web.dto.ticket.mapper;

import com.web.dto.baggage.mapper.BaggageMapper;
import com.web.dto.ticket.TicketCreateRequest;
import com.web.dto.ticket.TicketResponse;
import com.web.entity.Ticket;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper(componentModel = "spring", uses = {BaggageMapper.class})
public interface TicketMapper {
    
    // Entity → Response con info mínima de tramos
    @Mapping(target = "tripId", source = "trip.id")
    @Mapping(target = "routeName", source = "trip.route.name")
    @Mapping(target = "tripDate", source = "trip.tripDate")
    @Mapping(target = "departureTime", source = "trip.departureTime")
    @Mapping(target = "passengerId", source = "passenger.id")
    @Mapping(target = "passengerName", source = "passenger.name")
    @Mapping(target = "passengerEmail", source = "passenger.email")
    @Mapping(target = "fromStopId", source = "fromStop.id")
    @Mapping(target = "fromStopName", source = "fromStop.name")
    @Mapping(target = "fromStopOrder", source = "fromStop.order")
    @Mapping(target = "toStopId", source = "toStop.id")
    @Mapping(target = "toStopName", source = "toStop.name")
    @Mapping(target = "toStopOrder", source = "toStop.order")
    @Mapping(target = "baggage", source = "baggage")
    TicketResponse toResponse(Ticket ticket);
    
    List<TicketResponse> toResponseList(List<Ticket> tickets);
    
    // Request → Entity (sin relaciones, se setean en servicio)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "trip", ignore = true)
    @Mapping(target = "passenger", ignore = true)
    @Mapping(target = "fromStop", ignore = true)
    @Mapping(target = "toStop", ignore = true)
    @Mapping(target = "status", constant = "SOLD")
    @Mapping(target = "qrCode", ignore = true) // Se genera en servicio
    @Mapping(target = "purchasedAt", ignore = true)
    @Mapping(target = "baggage", ignore = true) // Se maneja separadamente
    @Mapping(target = "boardedAt", ignore = true)
    @Mapping(target = "refundAmount", ignore = true)
    @Mapping(target = "cancelledAt", ignore = true)
    @Mapping(target = "noShowFee", ignore = true)
    @Mapping(target = "channel", ignore = true)
    @Mapping(target = "soldBy", ignore = true)
    @Mapping(target = "offlineClientId", ignore = true)
    @Mapping(target = "syncedAt", ignore = true)
    Ticket toEntity(TicketCreateRequest request);
}

