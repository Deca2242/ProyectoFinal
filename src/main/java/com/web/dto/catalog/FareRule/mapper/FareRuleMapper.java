package com.web.dto.catalog.FareRule.mapper;

import com.web.dto.catalog.FareRule.FareRuleCreateRequest;
import com.web.dto.catalog.FareRule.FareRuleResponse;
import com.web.dto.catalog.FareRule.FareRuleUpdateRequest;
import com.web.entity.FareRule;
import org.mapstruct.*;

import java.util.List;

@Mapper(componentModel = "spring")
public interface FareRuleMapper {

    // Entity → Response
    @Mapping(target = "routeId", source = "route.id")
    @Mapping(target = "fromStopId", source = "fromStop.id")
    @Mapping(target = "fromStopName", source = "fromStop.name")
    @Mapping(target = "fromStopOrder", source = "fromStop.order")
    @Mapping(target = "toStopId", source = "toStop.id")
    @Mapping(target = "toStopName", source = "toStop.name")
    @Mapping(target = "toStopOrder", source = "toStop.order")
    FareRuleResponse toResponse(FareRule fareRule);

    List<FareRuleResponse> toResponseList(List<FareRule> fareRules);

    // Request → Entity (ruta, paradas y descuentos validados se asignan en el servicio)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "route", ignore = true)
    @Mapping(target = "fromStop", ignore = true)
    @Mapping(target = "toStop", ignore = true)
    @Mapping(target = "discounts", ignore = true)
    @Mapping(target = "dynamicPricingEnabled", ignore = true)
    FareRule toEntity(FareRuleCreateRequest request);

    // Update parcial (solo el precio; el resto se valida en el servicio)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "route", ignore = true)
    @Mapping(target = "fromStop", ignore = true)
    @Mapping(target = "toStop", ignore = true)
    @Mapping(target = "discounts", ignore = true)
    @Mapping(target = "dynamicPricingEnabled", ignore = true)
    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    void updateEntityFromRequest(FareRuleUpdateRequest request, @MappingTarget FareRule fareRule);
}
