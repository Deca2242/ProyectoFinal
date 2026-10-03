package com.web.dto.notification.mapper;

import com.web.dto.notification.NotificationResponse;
import com.web.entity.Notification;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper(componentModel = "spring")
public interface NotificationMapper {

    @Mapping(target = "userId", source = "user.id")
    @Mapping(target = "tripId", source = "trip.id")
    @Mapping(target = "ticketId", source = "ticket.id")
    NotificationResponse toResponse(Notification notification);

    List<NotificationResponse> toResponseList(List<Notification> notifications);
}
