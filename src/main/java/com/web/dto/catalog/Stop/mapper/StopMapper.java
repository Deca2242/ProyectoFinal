package com.web.dto.catalog.Stop.mapper;

import com.web.dto.catalog.Stop.StopCreateRequest;
import com.web.dto.catalog.Stop.StopResponse;
import com.web.dto.catalog.Stop.StopUpdateRequest;
import com.web.entity.Stop;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;

import java.util.List;

@Mapper(componentModel = "spring")
public interface StopMapper {
    
    // Entity → Response
    StopResponse toResponse(Stop stop);
    
    List<StopResponse> toResponseList(List<Stop> stops);
    
    // Request → Entity
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "route", ignore = true)
    @Mapping(target = "ticketsFrom", ignore = true)
    @Mapping(target = "ticketsTo", ignore = true)
    @Mapping(target = "fareRulesFrom", ignore = true)
    @Mapping(target = "fareRulesTo", ignore = true)
    @Mapping(target = "parcelsFrom", ignore = true)
    @Mapping(target = "parcelsTo", ignore = true)
    Stop toEntity(StopCreateRequest request);

    // Update parcial (nombre y coordenadas); la ruta y el orden no se modifican
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "route", ignore = true)
    @Mapping(target = "order", ignore = true)
    @Mapping(target = "ticketsFrom", ignore = true)
    @Mapping(target = "ticketsTo", ignore = true)
    @Mapping(target = "fareRulesFrom", ignore = true)
    @Mapping(target = "fareRulesTo", ignore = true)
    @Mapping(target = "parcelsFrom", ignore = true)
    @Mapping(target = "parcelsTo", ignore = true)
    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    void updateEntityFromRequest(StopUpdateRequest request, @MappingTarget Stop stop);
}
