package com.web.dto.catalog.Route.mapper;

import com.web.dto.catalog.Route.RouteCreateRequest;
import com.web.dto.catalog.Route.RouteDetailResponse;
import com.web.dto.catalog.Route.RouteResponse;
import com.web.dto.catalog.Route.RouteUpdateRequest;
import com.web.dto.catalog.Stop.mapper.StopMapper;
import com.web.entity.Route;
import org.mapstruct.*;

import java.util.List;

@Mapper(componentModel = "spring", uses = {StopMapper.class})
public interface RouteMapper {
    
    // Entity → Response simple (sin stops)
    RouteResponse toResponse(Route route);
    
    List<RouteResponse> toResponseList(List<Route> routes);
    
    // Entity → Response detallado (con stops)
    @Mapping(target = "stops", source = "stops")
    RouteDetailResponse toDetailResponse(Route route);
    
    // Request → Entity
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "isActive", constant = "true")
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "stops", ignore = true)
    @Mapping(target = "trips", ignore = true)
    @Mapping(target = "fareRules", ignore = true)
    Route toEntity(RouteCreateRequest request);
    
    // Update parcial
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "code", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "stops", ignore = true)
    @Mapping(target = "trips", ignore = true)
    @Mapping(target = "fareRules", ignore = true)
    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    void updateEntityFromRequest(RouteUpdateRequest request, @MappingTarget Route route);
}

