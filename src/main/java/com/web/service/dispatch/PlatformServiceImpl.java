package com.web.service.dispatch;

import com.web.dto.notification.PlatformUpdateResponse;
import com.web.entity.Trip;
import com.web.exception.InvalidStateTransitionException;
import com.web.exception.ResourceNotFoundException;
import com.web.repository.TripRepository;
import com.web.service.notification.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Service
@RequiredArgsConstructor
public class PlatformServiceImpl implements PlatformService {

    private final TripRepository tripRepository;
    private final NotificationService notificationService;

    @Override
    @Transactional
    public PlatformUpdateResponse updatePlatform(Long tripId, String platform) {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new ResourceNotFoundException("Viaje", tripId));

        // El andén solo tiene sentido antes de la salida (422: estado inválido para la operación)
        if (trip.getStatus() != Trip.TripStatus.SCHEDULED && trip.getStatus() != Trip.TripStatus.BOARDING) {
            throw new InvalidStateTransitionException("Solo se puede cambiar el andén de un viaje programado o en abordaje (estado: "
                    + trip.getStatus() + ")");
        }

        String newPlatform = platform.trim().toUpperCase(Locale.ROOT);
        String oldPlatform = trip.getPlatform();
        boolean changed = !newPlatform.equals(oldPlatform);

        if (changed) {
            trip.setPlatform(newPlatform);
            tripRepository.save(trip);
            notificationService.notifyPlatformChanged(trip, oldPlatform);
        }

        return new PlatformUpdateResponse(trip.getId(), trip.getStatus(), newPlatform, oldPlatform, changed);
    }
}
