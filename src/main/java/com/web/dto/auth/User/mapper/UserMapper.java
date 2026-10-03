package com.web.dto.auth.User.mapper;

import com.web.dto.auth.User.UserResponse;
import com.web.dto.auth.User.UserUpdateRequest;
import com.web.dto.auth.Login.RegisterRequest;
import com.web.entity.User;
import org.mapstruct.*;

import java.util.List;

@Mapper(componentModel = "spring")
public interface UserMapper {
    
    // Entity → Response
    UserResponse toResponse(User user);
    
    List<UserResponse> toResponseList(List<User> users);
    
    // Request → Entity (sin password)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "passwordHash", ignore = true)
    @Mapping(target = "status", constant = "ACTIVE")
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "seatHolds", ignore = true)
    @Mapping(target = "tickets", ignore = true)
    @Mapping(target = "driverAssignments", ignore = true)
    @Mapping(target = "dispatcherAssignments", ignore = true)
    @Mapping(target = "reportedIncidents", ignore = true)
    @Mapping(target = "passwordChangedAt", ignore = true)
    User toEntity(RegisterRequest request);
    
    // Update parcial
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "email", ignore = true)
    @Mapping(target = "role", ignore = true)
    @Mapping(target = "passwordHash", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "seatHolds", ignore = true)
    @Mapping(target = "tickets", ignore = true)
    @Mapping(target = "driverAssignments", ignore = true)
    @Mapping(target = "dispatcherAssignments", ignore = true)
    @Mapping(target = "reportedIncidents", ignore = true)
    @Mapping(target = "passwordChangedAt", ignore = true)
    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    void updateEntityFromRequest(UserUpdateRequest request, @MappingTarget User user);
}

