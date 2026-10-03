package com.web.dto.baggage.mapper;

import com.web.dto.baggage.BaggageCreateRequest;
import com.web.dto.baggage.BaggageResponse;
import com.web.dto.baggage.BaggageUpdateRequest;
import com.web.entity.Baggage;
import org.mapstruct.*;

import java.util.List;

@Mapper(componentModel = "spring")
public interface BaggageMapper {
    
    // Entity → Response
    @Mapping(target = "ticketId", source = "ticket.id")
    BaggageResponse toResponse(Baggage baggage);
    
    List<BaggageResponse> toResponseList(List<Baggage> baggages);
    
    // Request → Entity
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "ticket", ignore = true)
    @Mapping(target = "tagCode", ignore = true) // Se genera en servicio
    @Mapping(target = "compartment", ignore = true) // El servicio normaliza el maletero
    @Mapping(target = "createdAt", ignore = true)
    Baggage toEntity(BaggageCreateRequest request);
    
    // Update parcial
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "ticket", ignore = true)
    @Mapping(target = "tagCode", ignore = true)
    @Mapping(target = "compartment", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    void updateEntityFromRequest(BaggageUpdateRequest request, @MappingTarget Baggage baggage);
}

