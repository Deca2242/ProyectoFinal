package com.web.dto.dispatch.OverbookingPolicy.mapper;

import com.web.dto.dispatch.OverbookingPolicy.OverbookingPolicyCreateRequest;
import com.web.dto.dispatch.OverbookingPolicy.OverbookingPolicyResponse;
import com.web.entity.OverbookingPolicy;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper(componentModel = "spring")
public interface OverbookingPolicyMapper {

    // Entity → Response
    @Mapping(target = "routeId", source = "route.id")
    @Mapping(target = "createdById", source = "createdBy.id")
    @Mapping(target = "createdByName", source = "createdBy.name")
    OverbookingPolicyResponse toResponse(OverbookingPolicy policy);

    List<OverbookingPolicyResponse> toResponseList(List<OverbookingPolicy> policies);

    // Request → Entity (la ruta y el creador se asignan en el servicio)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "route", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    OverbookingPolicy toEntity(OverbookingPolicyCreateRequest request);
}
