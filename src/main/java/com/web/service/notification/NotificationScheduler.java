package com.web.service.notification;

import com.web.entity.Trip;
import com.web.repository.TripRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

// Avisa a los pasajeros cuando su viaje en curso llegará en los próximos 15 minutos (una sola vez por viaje).
// Un viaje retrasado (ETA ya pasada y aún DEPARTED) que no recibió el aviso también se notifica
@Component
@RequiredArgsConstructor
public class NotificationScheduler {

    static final long ARRIVAL_WINDOW_MINUTES = 15;

    private final TripRepository tripRepository;
    private final NotificationService notificationService;

    @Scheduled(fixedRate = 60000)
    @Transactional
    public void notifyUpcomingArrivals() {
        LocalDateTime now = LocalDateTime.now();
        List<Trip> trips = tripRepository.findDepartedTripsArrivingBy(now.plusMinutes(ARRIVAL_WINDOW_MINUTES));
        for (Trip trip : trips) {
            notificationService.notifyArrivalSoon(trip);
            trip.setArrivalNotified(true);
            tripRepository.save(trip);
        }
    }
}
