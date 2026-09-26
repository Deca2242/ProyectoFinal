package com.web.service.ticket;

import com.web.dto.ticket.reservations.SeatHoldCreateRequest;
import com.web.dto.ticket.reservations.SeatHoldResponse;
import com.web.dto.ticket.reservations.mapper.SeatHoldMapper;
import com.web.entity.Bus;
import com.web.entity.SeatHold;
import com.web.entity.Trip;
import com.web.entity.User;
import com.web.exception.ResourceNotFoundException;
import com.web.exception.SeatNotAvailableException;
import com.web.repository.SeatHoldRepository;
import com.web.repository.StopRepository;
import com.web.repository.TicketRepository;
import com.web.repository.TripRepository;
import com.web.repository.UserRepository;
import com.web.service.admin.ConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;


@ExtendWith(MockitoExtension.class)
class SeatHoldServiceImplTest {

    @Mock
    private SeatHoldRepository seatHoldRepository;
    @Mock
    private TicketRepository ticketRepository;
    @Mock
    private TripRepository tripRepository;
    @Mock
    private StopRepository stopRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private SeatHoldMapper seatHoldMapper;
    @Mock
    private ConfigService configService;

    @InjectMocks
    private SeatHoldServiceImpl seatHoldService;

    private Trip trip;
    private User user;
    private SeatHold seatHold;
    private SeatHoldResponse seatHoldResponse;

    @BeforeEach
    void setUp() {
        Bus bus = Bus.builder()
                .id(1L)
                .capacity(40)
                .build();
        
        trip = Trip.builder()
                .id(1L)
                .bus(bus)
                .departureTime(LocalDateTime.now().plusDays(1))
                .build();

        user = User.builder()
                .id(1L)
                .name("John Doe")
                .email("john@example.com")
                .build();

        seatHold = SeatHold.builder()
                .id(1L)
                .trip(trip)
                .user(user)
                .seatNumber(10)
                .status(SeatHold.HoldStatus.HOLD)
                .expiresAt(LocalDateTime.now().plusMinutes(10))
                .build();

        seatHoldResponse = new SeatHoldResponse(
                1L, 1L, 10, 1L,
                LocalDateTime.now().plusMinutes(10),
                SeatHold.HoldStatus.HOLD,
                LocalDateTime.now()
        , null, null);
    }

    @Test
    void shouldCreateHold_WithValidRequest_ReturnSeatHoldResponse() {
        // Given
        SeatHoldCreateRequest request = new SeatHoldCreateRequest(1L, 10, 1L);

        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(seatHoldRepository.findOverlappingActiveHolds(eq(1L), eq(10), eq(Integer.MIN_VALUE), eq(Integer.MAX_VALUE), any(LocalDateTime.class)))
                .thenReturn(List.of());
        when(ticketRepository.isSeatAvailableForFullTrip(1L, 10)).thenReturn(true);
        when(configService.getHoldDurationMinutes()).thenReturn(10);
        when(seatHoldRepository.save(any(SeatHold.class))).thenAnswer(inv -> {
            SeatHold sh = inv.getArgument(0);
            sh.setId(1L);
            return sh;
        });
        when(seatHoldMapper.toResponse(any(SeatHold.class))).thenReturn(seatHoldResponse);

        // When
        SeatHoldResponse result = seatHoldService.createHold(request, 1L);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.id()).isEqualTo(1L);
        verify(tripRepository).findById(1L);
        verify(userRepository).findById(1L);
        verify(seatHoldRepository).save(any(SeatHold.class));
    }

    @Test
    void shouldCreateHold_WithExpirationIn10Minutes_SetCorrectExpiration() {
        // Given
        SeatHoldCreateRequest request = new SeatHoldCreateRequest(1L, 10, 1L);
        LocalDateTime now = LocalDateTime.now();

        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(seatHoldRepository.findOverlappingActiveHolds(eq(1L), eq(10), eq(Integer.MIN_VALUE), eq(Integer.MAX_VALUE), any(LocalDateTime.class)))
                .thenReturn(List.of());
        when(ticketRepository.isSeatAvailableForFullTrip(1L, 10)).thenReturn(true);
        when(configService.getHoldDurationMinutes()).thenReturn(10);
        when(seatHoldRepository.save(any(SeatHold.class))).thenAnswer(inv -> {
            SeatHold sh = inv.getArgument(0);
            sh.setId(1L);
            return sh;
        });
        when(seatHoldMapper.toResponse(any(SeatHold.class))).thenReturn(seatHoldResponse);

        // When
        seatHoldService.createHold(request, 1L);

        // Then
        verify(seatHoldRepository).save(argThat(sh -> {
            LocalDateTime expectedExpiration = now.plusMinutes(10);
            return sh.getExpiresAt().isAfter(now) && 
                   sh.getExpiresAt().isBefore(now.plusMinutes(11));
        }));
    }

    @Test
    void shouldCreateHold_WithSeatAlreadyHeld_ThrowException() {
        // Given
        SeatHoldCreateRequest request = new SeatHoldCreateRequest(1L, 10, 1L);
        SeatHold existingHold = SeatHold.builder()
                .id(2L)
                .user(User.builder().id(2L).build())
                .expiresAt(LocalDateTime.now().plusMinutes(5))
                .build();

        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(seatHoldRepository.findOverlappingActiveHolds(eq(1L), eq(10), eq(Integer.MIN_VALUE), eq(Integer.MAX_VALUE), any(LocalDateTime.class)))
                .thenReturn(List.of(existingHold));

        // When/Then
        assertThatThrownBy(() -> seatHoldService.createHold(request, 1L))
                .isInstanceOf(SeatNotAvailableException.class)
                .hasMessageContaining("hold activo");
    }

    @Test
    void shouldHasActiveHold_WithActiveHold_ReturnTrue() {
        // Given
        when(seatHoldRepository.findActiveHolds(eq(1L), eq(10), any(LocalDateTime.class)))
                .thenReturn(List.of(seatHold));

        // When
        boolean result = seatHoldService.hasActiveHold(1L, 10);

        // Then
        assertThat(result).isTrue();
        verify(seatHoldRepository).findActiveHolds(eq(1L), eq(10), any(LocalDateTime.class));
    }

    @Test
    void shouldReleaseHold_WithValidHoldId_UpdateStatusToSold() {
        // Given
        when(seatHoldRepository.findById(1L)).thenReturn(Optional.of(seatHold));
        when(seatHoldRepository.save(any(SeatHold.class))).thenReturn(seatHold);

        // When
        seatHoldService.releaseHold(1L);

        // Then
        verify(seatHoldRepository).save(argThat(sh -> 
            sh.getStatus() == SeatHold.HoldStatus.SOLD
        ));
    }

    @Test
    void shouldGetUserActiveHolds_WithValidUserId_ReturnList() {
        // Given
        List<SeatHold> holds = List.of(seatHold);
        List<SeatHoldResponse> responses = List.of(seatHoldResponse);

        when(seatHoldRepository.findByUserIdAndStatus(1L, SeatHold.HoldStatus.HOLD))
                .thenReturn(holds);
        when(seatHoldMapper.toResponseList(anyList())).thenReturn(responses);

        // When
        List<SeatHoldResponse> result = seatHoldService.getUserActiveHolds(1L);

        // Then
        assertThat(result).isNotNull();
        assertThat(result).hasSize(1);
        verify(seatHoldRepository).findByUserIdAndStatus(1L, SeatHold.HoldStatus.HOLD);
    }
}

