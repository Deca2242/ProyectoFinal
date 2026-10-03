package com.web.dto.notification;

import com.web.entity.Trip;

import java.io.Serializable;

// Resultado del cambio de andén: si cambió, se notificó a los pasajeros con ticket vendido
public record PlatformUpdateResponse(
        Long tripId,
        Trip.TripStatus status,
        String platform,
        String previousPlatform,
        boolean changed
) implements Serializable {
}
