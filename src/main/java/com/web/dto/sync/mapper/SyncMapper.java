package com.web.dto.sync.mapper;

import com.web.dto.sync.SyncConflictResponse;
import com.web.entity.SyncConflict;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper(componentModel = "spring")
public interface SyncMapper {

    @Mapping(target = "batchId", source = "batch.id")
    @Mapping(target = "deviceId", source = "batch.deviceId")
    @Mapping(target = "type", source = "batch.type")
    SyncConflictResponse toConflictResponse(SyncConflict conflict);

    List<SyncConflictResponse> toConflictResponseList(List<SyncConflict> conflicts);
}
