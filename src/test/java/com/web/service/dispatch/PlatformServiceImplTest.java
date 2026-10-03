package com.web.service.dispatch;

import com.web.dto.notification.PlatformUpdateResponse;
import com.web.entity.Trip;
import com.web.exception.BusinessException;
import com.web.exception.InvalidStateTransitionException;
import com.web.exception.ResourceNotFoundException;
import com.web.repository.TripRepository;
import com.web.service.notification.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

// Cambio de andén por el DISPATCHER con aviso a los pasajeros
@ExtendWith(MockitoExtension.class)
class PlatformServiceImplTest {

    @Mock
    private TripRepository tripRepository;
    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private PlatformServiceImpl platformService;

    private Trip trip;

    @BeforeEach
    void setUp() {
        trip = Trip.builder().id(1L).status(Trip.TripStatus.SCHEDULED).platform("B1").build();
    }

    @Test
    void shouldUpdatePlatform_WhenChanged_SaveAndNotifyWithPreviousPlatform() {
        // Given
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));

        // When
        PlatformUpdateResponse response = platformService.updatePlatform(1L, " a3 ");

        // Then: se normaliza el andén y se avisa después de guardar
        assertThat(response).isEqualTo(new PlatformUpdateResponse(1L, Trip.TripStatus.SCHEDULED, "A3", "B1", true));
        assertThat(trip.getPlatform()).isEqualTo("A3");
        InOrder inOrder = inOrder(tripRepository, notificationService);
        inOrder.verify(tripRepository).save(trip);
        inOrder.verify(notificationService).notifyPlatformChanged(trip, "B1");
    }

    @Test
    void shouldUpdatePlatform_FirstAssignment_NotifyWithoutPrevious() {
        // Given
        trip.setPlatform(null);
        trip.setStatus(Trip.TripStatus.BOARDING);
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));

        // When
        PlatformUpdateResponse response = platformService.updatePlatform(1L, "C2");

        // Then
        assertThat(response.changed()).isTrue();
        assertThat(response.previousPlatform()).isNull();
        verify(notificationService).notifyPlatformChanged(trip, null);
    }

    @Test
    void shouldUpdatePlatform_WhenSamePlatform_NotSaveNorNotify() {
        // Given
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));

        // When
        PlatformUpdateResponse response = platformService.updatePlatform(1L, "b1");

        // Then
        assertThat(response.changed()).isFalse();
        assertThat(response.platform()).isEqualTo("B1");
        verify(tripRepository, never()).save(any());
        verifyNoInteractions(notificationService);
    }

    @Test
    void shouldUpdatePlatform_WithNonExistentTrip_ThrowNotFound() {
        // Given
        when(tripRepository.findById(99L)).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> platformService.updatePlatform(99L, "A1"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("99");
        verifyNoInteractions(notificationService);
    }

    @ParameterizedTest
    @EnumSource(value = Trip.TripStatus.class, names = {"DEPARTED", "ARRIVED", "CANCELLED"})
    void shouldUpdatePlatform_WithTripAlreadyGone_ThrowInvalidStateTransition(Trip.TripStatus status) {
        // Given
        trip.setStatus(status);
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));

        // When/Then: 422, estado inválido para la operación
        assertThatThrownBy(() -> platformService.updatePlatform(1L, "A1"))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining(status.name())
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(be.getCode()).isEqualTo("INVALID_STATE_TRANSITION");
                });
        verify(tripRepository, never()).save(any());
        verifyNoInteractions(notificationService);
    }
}
