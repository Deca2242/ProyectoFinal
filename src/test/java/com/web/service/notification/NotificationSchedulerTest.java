package com.web.service.notification;

import com.web.entity.Trip;
import com.web.repository.TripRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

// Tarea programada de aviso de llegada próxima (viajes DEPARTED que llegan en los próximos 15 minutos o ya retrasados)
@ExtendWith(MockitoExtension.class)
class NotificationSchedulerTest {

    @Mock
    private TripRepository tripRepository;
    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private NotificationScheduler scheduler;

    @Test
    void shouldNotifyUpcomingArrivals_NotifyAndMarkEachTrip() {
        // Given
        Trip first = Trip.builder().id(1L).status(Trip.TripStatus.DEPARTED).build();
        Trip second = Trip.builder().id(2L).status(Trip.TripStatus.DEPARTED).build();
        when(tripRepository.findDepartedTripsArrivingBy(any())).thenReturn(List.of(first, second));

        // When
        scheduler.notifyUpcomingArrivals();

        // Then: se notifica antes de marcar y guardar cada viaje
        InOrder inOrder = inOrder(notificationService, tripRepository);
        inOrder.verify(notificationService).notifyArrivalSoon(first);
        inOrder.verify(tripRepository).save(first);
        inOrder.verify(notificationService).notifyArrivalSoon(second);
        inOrder.verify(tripRepository).save(second);
        assertThat(first.getArrivalNotified()).isTrue();
        assertThat(second.getArrivalNotified()).isTrue();
    }

    @Test
    void shouldNotifyUpcomingArrivals_QueryUpToFifteenMinutesFromNowWithoutLowerBound() {
        // Given
        when(tripRepository.findDepartedTripsArrivingBy(any())).thenReturn(List.of());
        LocalDateTime before = LocalDateTime.now();

        // When
        scheduler.notifyUpcomingArrivals();

        // Then
        ArgumentCaptor<LocalDateTime> to = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(tripRepository).findDepartedTripsArrivingBy(to.capture());
        assertThat(to.getValue()).isCloseTo(before.plusMinutes(15), within(Duration.ofSeconds(5)));
        verifyNoInteractions(notificationService);
        verify(tripRepository, never()).save(any());
    }

    @Test
    void shouldNotifyUpcomingArrivals_WithEtaAlreadyPassed_NotifyDelayedTrip() {
        // Given: viaje retrasado, su ETA pasó hace 20 minutos y sigue DEPARTED sin aviso
        Trip delayed = Trip.builder().id(3L).status(Trip.TripStatus.DEPARTED)
                .arrivalEta(LocalDateTime.now().minusMinutes(20)).build();
        when(tripRepository.findDepartedTripsArrivingBy(any())).thenReturn(List.of(delayed));

        // When
        scheduler.notifyUpcomingArrivals();

        // Then
        verify(notificationService).notifyArrivalSoon(delayed);
        verify(tripRepository).save(delayed);
        assertThat(delayed.getArrivalNotified()).isTrue();
    }
}
