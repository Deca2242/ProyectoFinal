package com.web.service.catalog;

import com.web.dto.catalog.Bus.BusCreateRequest;
import com.web.dto.catalog.Bus.BusResponse;
import com.web.dto.catalog.Bus.BusUpdateRequest;
import com.web.dto.catalog.Bus.mapper.BusMapper;
import com.web.dto.catalog.Seat.SeatResponse;
import com.web.dto.catalog.Seat.SeatUpdateRequest;
import com.web.dto.catalog.Seat.mapper.SeatMapper;
import com.web.entity.Bus;
import com.web.entity.Seat;
import com.web.exception.BusinessException;
import com.web.exception.ResourceNotFoundException;
import com.web.repository.BusRepository;
import com.web.repository.SeatRepository;
import com.web.repository.TripRepository;
import org.springframework.http.HttpStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;


@ExtendWith(MockitoExtension.class)
class BusServiceImplTest {

    @Mock
    private BusRepository busRepository;
    @Mock
    private BusMapper busMapper;

    @Mock
    private TripRepository tripRepository;
    @Mock
    private SeatRepository seatRepository;
    @Mock
    private SeatMapper seatMapper;

    @InjectMocks
    private BusServiceImpl busService;

    private Bus bus;
    private BusResponse busResponse;

    @BeforeEach
    void setUp() {
        bus = Bus.builder()
                .id(1L)
                .plate("ABC123")
                .capacity(40)
                .status(Bus.BusStatus.ACTIVE)
                .build();

        busResponse = new BusResponse(
                1L, "ABC123", 40, null, Bus.BusStatus.ACTIVE
        );
    }

    @Test
    void shouldCreateBus_WithValidRequest_ReturnBusResponse() {
        // Given
        BusCreateRequest request = new BusCreateRequest(
                "ABC123", 40, null
        );

        when(busRepository.findByPlate("ABC123")).thenReturn(Optional.empty());
        when(busMapper.toEntity(request)).thenReturn(bus);
        when(busRepository.save(any(Bus.class))).thenAnswer(inv -> {
            Bus b = inv.getArgument(0);
            b.setId(1L);
            return b;
        });
        when(busMapper.toResponse(any(Bus.class))).thenReturn(busResponse);

        // When
        BusResponse result = busService.createBus(request);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.id()).isEqualTo(1L);
        verify(busRepository).findByPlate("ABC123");
        verify(busRepository).save(any(Bus.class));
    }

    @Test
    void shouldCreateBus_GenerateStandardSeatsUpToCapacity() {
        // Given
        bus.setCapacity(3);
        BusCreateRequest request = new BusCreateRequest("ABC123", 3, null);
        when(busRepository.findByPlate("ABC123")).thenReturn(Optional.empty());
        when(busMapper.toEntity(request)).thenReturn(bus);
        when(busRepository.save(bus)).thenReturn(bus);
        when(busMapper.toResponse(bus)).thenReturn(busResponse);

        // When
        busService.createBus(request);

        // Then: sillas 1..3 STANDARD del bus creado
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Seat>> captor = ArgumentCaptor.forClass(List.class);
        verify(seatRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).extracting(Seat::getSeatNumber).containsExactly(1, 2, 3);
        assertThat(captor.getValue()).allSatisfy(seat -> {
            assertThat(seat.getSeatType()).isEqualTo(Seat.SeatType.STANDARD);
            assertThat(seat.getBus()).isSameAs(bus);
        });
    }

    @Test
    void shouldCreateBus_NormalizePlateToUppercaseWithoutSpaces() {
        // Given
        BusCreateRequest request = new BusCreateRequest(" abc 123 ", 40, null);
        when(busRepository.findByPlate("ABC123")).thenReturn(Optional.empty());
        when(busMapper.toEntity(request)).thenReturn(Bus.builder().plate(" abc 123 ").capacity(40).build());
        when(busRepository.save(any(Bus.class))).thenAnswer(inv -> inv.getArgument(0));
        when(busMapper.toResponse(any(Bus.class))).thenReturn(busResponse);

        // When
        busService.createBus(request);

        // Then
        verify(busRepository).save(argThat(b -> "ABC123".equals(b.getPlate())));
    }

    @Test
    void shouldGetAllBuses_ReturnBusList() {
        // Given
        List<Bus> buses = List.of(bus);
        List<BusResponse> responses = List.of(busResponse);

        when(busRepository.findAll()).thenReturn(buses);
        when(busMapper.toResponseList(buses)).thenReturn(responses);

        // When
        List<BusResponse> result = busService.getAllBuses();

        // Then
        assertThat(result).isNotNull();
        assertThat(result).hasSize(1);
        verify(busRepository).findAll();
    }

    @Test
    void shouldGetBusById_WithValidId_ReturnBusResponse() {
        // Given
        when(busRepository.findById(1L)).thenReturn(Optional.of(bus));
        when(busMapper.toResponse(bus)).thenReturn(busResponse);

        // When
        BusResponse result = busService.getBusById(1L);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.id()).isEqualTo(1L);
        verify(busRepository).findById(1L);
    }

    @Test
    void shouldUpdateBus_WithValidRequest_UpdateBus() {
        // Given
        BusUpdateRequest request = new BusUpdateRequest(
                45, null, Bus.BusStatus.ACTIVE
        );

        when(busRepository.findById(1L)).thenReturn(Optional.of(bus));
        when(busRepository.save(any(Bus.class))).thenReturn(bus);
        when(busMapper.toResponse(any(Bus.class))).thenReturn(busResponse);

        // When
        BusResponse result = busService.updateBus(1L, request);

        // Then
        assertThat(result).isNotNull();
        verify(busMapper).updateEntityFromRequest(request, bus);
        verify(busRepository).save(bus);
    }

    @Test
    void shouldUpdateBus_ReduceCapacityBelowSoldSeats_ThrowConflict() {
        // Given: hay un tiquete vendido en la silla 38 de un viaje futuro
        BusUpdateRequest request = new BusUpdateRequest(30, null, null);
        when(busRepository.findById(1L)).thenReturn(Optional.of(bus));
        when(tripRepository.findMaxSoldSeatNumberInFutureTrips(eq(1L), any(LocalDateTime.class))).thenReturn(38);

        // When/Then
        assertThatThrownBy(() -> busService.updateBus(1L, request))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(be.getCode()).isEqualTo("CAPACITY_BELOW_SOLD_SEATS");
                });
        verify(busRepository, never()).save(any(Bus.class));
        verifyNoInteractions(busMapper, seatRepository);
    }

    @Test
    void shouldUpdateBus_ReduceCapacityAboveSoldSeats_DeleteExtraSeats() {
        // Given: la silla vendida más alta es la 20
        BusUpdateRequest request = new BusUpdateRequest(30, null, null);
        when(busRepository.findById(1L)).thenReturn(Optional.of(bus));
        when(tripRepository.findMaxSoldSeatNumberInFutureTrips(eq(1L), any(LocalDateTime.class))).thenReturn(20);
        doAnswer(inv -> {
            bus.setCapacity(30);
            return null;
        }).when(busMapper).updateEntityFromRequest(request, bus);
        when(busRepository.save(bus)).thenReturn(bus);
        when(seatRepository.findByBusIdOrderBySeatNumberAsc(1L)).thenReturn(
                java.util.stream.IntStream.rangeClosed(1, 30)
                        .mapToObj(n -> Seat.builder().bus(bus).seatNumber(n).seatType(Seat.SeatType.STANDARD).build())
                        .toList());
        when(busMapper.toResponse(bus)).thenReturn(busResponse);

        // When
        busService.updateBus(1L, request);

        // Then
        verify(seatRepository).deleteByBusIdAndSeatNumberGreaterThan(1L, 30);
        verify(seatRepository, never()).saveAll(anyList());
    }

    @Test
    void shouldUpdateBus_IncreaseCapacity_CreateMissingSeats() {
        // Given: el bus tiene las sillas 1..40 y pasa a 42
        BusUpdateRequest request = new BusUpdateRequest(42, null, null);
        when(busRepository.findById(1L)).thenReturn(Optional.of(bus));
        doAnswer(inv -> {
            bus.setCapacity(42);
            return null;
        }).when(busMapper).updateEntityFromRequest(request, bus);
        when(busRepository.save(bus)).thenReturn(bus);
        when(seatRepository.findByBusIdOrderBySeatNumberAsc(1L)).thenReturn(
                java.util.stream.IntStream.rangeClosed(1, 40)
                        .mapToObj(n -> Seat.builder().bus(bus).seatNumber(n).seatType(Seat.SeatType.STANDARD).build())
                        .toList());
        when(busMapper.toResponse(bus)).thenReturn(busResponse);

        // When
        busService.updateBus(1L, request);

        // Then: no consulta tiquetes vendidos (no se reduce) y crea las sillas 41 y 42
        verify(tripRepository, never()).findMaxSoldSeatNumberInFutureTrips(any(), any());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Seat>> captor = ArgumentCaptor.forClass(List.class);
        verify(seatRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).extracting(Seat::getSeatNumber).containsExactly(41, 42);
    }

    @ParameterizedTest
    @EnumSource(value = Bus.BusStatus.class, names = {"MAINTENANCE", "RETIRED"})
    void shouldUpdateBus_ToInactiveStatusWithPendingTrips_ThrowConflict(Bus.BusStatus status) {
        // Given
        BusUpdateRequest request = new BusUpdateRequest(null, null, status);
        when(busRepository.findById(1L)).thenReturn(Optional.of(bus));
        when(tripRepository.existsPendingTripsByBus(eq(1L), any(LocalDateTime.class))).thenReturn(true);

        // When/Then
        assertThatThrownBy(() -> busService.updateBus(1L, request))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(be.getCode()).isEqualTo("BUS_HAS_TRIPS");
                });
        assertThat(bus.getStatus()).isEqualTo(Bus.BusStatus.ACTIVE);
        verify(busRepository, never()).save(any(Bus.class));
    }

    @Test
    void shouldDeleteBus_SetRetiredStatus() {
        // Given
        when(busRepository.findById(1L)).thenReturn(Optional.of(bus));
        when(busRepository.save(any(Bus.class))).thenReturn(bus);

        // When
        busService.deleteBus(1L);

        // Then
        verify(busRepository).save(argThat(b ->
            b.getStatus() == Bus.BusStatus.RETIRED
        ));
    }

    @Test
    void shouldDeleteBus_WithScheduledTrips_ThrowConflict() {
        // Given
        when(busRepository.findById(1L)).thenReturn(Optional.of(bus));
        when(tripRepository.existsPendingTripsByBus(eq(1L), any(LocalDateTime.class))).thenReturn(true);

        // When/Then
        assertThatThrownBy(() -> busService.deleteBus(1L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(be.getCode()).isEqualTo("BUS_HAS_TRIPS");
                });
        assertThat(bus.getStatus()).isEqualTo(Bus.BusStatus.ACTIVE);
        verify(busRepository, never()).save(any(Bus.class));
    }

    @Test
    void shouldGetAvailableBuses_WithDate_ReturnAvailableList() {
        // Given
        List<Bus> buses = List.of(bus);
        List<BusResponse> responses = List.of(busResponse);

        when(busRepository.findAll()).thenReturn(buses);
        when(busMapper.toResponseList(anyList())).thenReturn(responses);

        // When
        List<BusResponse> result = busService.getAvailableBuses(LocalDate.now(), null, null);

        // Then
        assertThat(result).isNotNull();
        assertThat(result).hasSize(1);
        verify(busRepository).findAll();
    }
    @Test
    void shouldCreateBus_WithDuplicatePlate_ThrowConflict() {
        // Given
        BusCreateRequest request = new BusCreateRequest("ABC123", 40, null);
        when(busRepository.findByPlate("ABC123")).thenReturn(Optional.of(bus));

        // When/Then
        assertThatThrownBy(() -> busService.createBus(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ABC123")
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(be.getCode()).isEqualTo("PLATE_EXISTS");
                });
        verify(busRepository, never()).save(any(Bus.class));
        verifyNoInteractions(busMapper);
    }

    @Test
    void shouldGetBusByPlate_WithValidPlate_ReturnBusResponse() {
        // Given
        when(busRepository.findByPlate("ABC123")).thenReturn(Optional.of(bus));
        when(busMapper.toResponse(bus)).thenReturn(busResponse);

        // When: la placa se normaliza igual que al crear
        BusResponse result = busService.getBusByPlate("abc 123");

        // Then
        assertThat(result).isEqualTo(busResponse);
        verify(busRepository).findByPlate("ABC123");
    }

    @Test
    void shouldGetBusByPlate_WithNonExistentPlate_ThrowResourceNotFoundException() {
        // Given
        when(busRepository.findByPlate("ZZZ999")).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> busService.getBusByPlate("ZZZ999"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("ZZZ999");
        verifyNoInteractions(busMapper);
    }

    @Test
    void shouldGetBusById_WithNonExistentId_ThrowResourceNotFoundException() {
        // Given
        when(busRepository.findById(99L)).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> busService.getBusById(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("99");
        verifyNoInteractions(busMapper);
    }

    @Test
    void shouldUpdateBus_WithNonExistentId_ThrowResourceNotFoundException() {
        // Given
        BusUpdateRequest request = new BusUpdateRequest(45, null, Bus.BusStatus.ACTIVE);
        when(busRepository.findById(99L)).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> busService.updateBus(99L, request))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(busRepository, never()).save(any(Bus.class));
        verifyNoInteractions(busMapper);
    }

    @Test
    void shouldDeleteBus_WithNonExistentId_ThrowResourceNotFoundException() {
        // Given
        when(busRepository.findById(99L)).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> busService.deleteBus(99L))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(busRepository, never()).save(any(Bus.class));
    }

    @Test
    void shouldDeleteBus_WithValidId_NotDeletePhysically() {
        // Given
        when(busRepository.findById(1L)).thenReturn(Optional.of(bus));

        // When
        busService.deleteBus(1L);

        // Then: el bus se retira (borrado lógico) en lugar de borrarse
        assertThat(bus.getStatus()).isEqualTo(Bus.BusStatus.RETIRED);
        verify(busRepository).save(bus);
        verify(busRepository, never()).delete(any(Bus.class));
    }

    @Test
    void shouldGetAvailableBuses_WithMixedStatuses_ReturnOnlyActive() {
        // Given
        Bus inMaintenance = Bus.builder().id(2L).plate("DEF456").capacity(30)
                .status(Bus.BusStatus.MAINTENANCE).build();
        Bus retired = Bus.builder().id(3L).plate("GHI789").capacity(20)
                .status(Bus.BusStatus.RETIRED).build();
        Bus otherActive = Bus.builder().id(4L).plate("JKL012").capacity(45)
                .status(Bus.BusStatus.ACTIVE).build();
        when(busRepository.findAll()).thenReturn(List.of(bus, inMaintenance, retired, otherActive));
        when(busMapper.toResponseList(anyList())).thenReturn(List.of(busResponse));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Bus>> captor = ArgumentCaptor.forClass(List.class);

        // When
        busService.getAvailableBuses(LocalDate.of(2026, 1, 15), null, null);

        // Then: solo se mapean los buses ACTIVE
        verify(busMapper).toResponseList(captor.capture());
        assertThat(captor.getValue()).containsExactly(bus, otherActive);
    }

    @Test
    void shouldGetAvailableBuses_WithoutActiveBuses_ReturnEmptyList() {
        // Given
        bus.setStatus(Bus.BusStatus.MAINTENANCE);
        when(busRepository.findAll()).thenReturn(List.of(bus));
        when(busMapper.toResponseList(List.of())).thenReturn(List.of());

        // When
        List<BusResponse> result = busService.getAvailableBuses(LocalDate.of(2026, 1, 15), null, null);

        // Then
        assertThat(result).isEmpty();
        verify(busMapper).toResponseList(List.of());
    }

    @Test
    void shouldGetAvailableBuses_WithBusAlreadyScheduled_ExcludeIt() {
        // Given
        Bus busyBus = Bus.builder().id(2L).plate("XYZ789").capacity(40).status(Bus.BusStatus.ACTIVE).build();
        LocalDate date = LocalDate.of(2026, 1, 15);
        when(busRepository.findAll()).thenReturn(List.of(bus, busyBus));
        when(tripRepository.findBusIdsWithTripsOnDate(date)).thenReturn(List.of(2L));
        when(busMapper.toResponseList(anyList())).thenReturn(List.of(busResponse));

        // When
        busService.getAvailableBuses(date, null, null);

        // Then: solo se mapea el bus sin viaje ese día
        verify(busMapper).toResponseList(argThat(list -> list.size() == 1 && list.get(0).getId().equals(bus.getId())));
    }

    @Test
    void shouldGetAvailableBuses_WithTimeSlot_ExcludeOnlyOverlappingBuses() {
        // Given: el bus 2 tiene un viaje que se solapa con la franja; el día no importa
        Bus busyBus = Bus.builder().id(2L).plate("XYZ789").capacity(40).status(Bus.BusStatus.ACTIVE).build();
        LocalDateTime departure = LocalDateTime.of(2026, 1, 15, 14, 0);
        LocalDateTime arrival = departure.plusHours(4);
        when(busRepository.findAll()).thenReturn(List.of(bus, busyBus));
        when(tripRepository.findBusIdsWithOverlappingTrips(departure, arrival)).thenReturn(List.of(2L));
        when(busMapper.toResponseList(anyList())).thenReturn(List.of(busResponse));

        // When
        busService.getAvailableBuses(LocalDate.of(2026, 1, 15), departure, arrival);

        // Then
        verify(busMapper).toResponseList(argThat(list -> list.size() == 1 && list.get(0).getId().equals(bus.getId())));
        verify(tripRepository, never()).findBusIdsWithTripsOnDate(any());
    }

    @Test
    void shouldGetAvailableBuses_WithIncompleteTimeSlot_ThrowBadRequest() {
        // When/Then: falta la llegada
        assertThatThrownBy(() -> busService.getAvailableBuses(null, LocalDateTime.of(2026, 1, 15, 14, 0), null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(busRepository, tripRepository);
    }

    // ---------- Sillas ----------

    @Test
    void shouldGetSeats_ReturnSeatsOfBus() {
        // Given
        List<Seat> seats = List.of(Seat.builder().id(1L).bus(bus).seatNumber(1).seatType(Seat.SeatType.STANDARD).build());
        List<SeatResponse> responses = List.of(new SeatResponse(1L, 1L, 1, Seat.SeatType.STANDARD));
        when(busRepository.existsById(1L)).thenReturn(true);
        when(seatRepository.findByBusIdOrderBySeatNumberAsc(1L)).thenReturn(seats);
        when(seatMapper.toResponseList(seats)).thenReturn(responses);

        // When
        List<SeatResponse> result = busService.getSeats(1L);

        // Then
        assertThat(result).isSameAs(responses);
    }

    @Test
    void shouldGetSeats_WithNonExistentBus_ThrowResourceNotFound() {
        // Given
        when(busRepository.existsById(99L)).thenReturn(false);

        // When/Then
        assertThatThrownBy(() -> busService.getSeats(99L)).isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(seatRepository);
    }

    @Test
    void shouldUpdateSeat_MarkPreferential() {
        // Given
        Seat seat = Seat.builder().id(5L).bus(bus).seatNumber(3).seatType(Seat.SeatType.STANDARD).build();
        SeatResponse response = new SeatResponse(5L, 1L, 3, Seat.SeatType.PREFERENTIAL);
        when(busRepository.existsById(1L)).thenReturn(true);
        when(seatRepository.findByBusIdAndSeatNumber(1L, 3)).thenReturn(Optional.of(seat));
        when(seatRepository.save(seat)).thenReturn(seat);
        when(seatMapper.toResponse(seat)).thenReturn(response);

        // When
        SeatResponse result = busService.updateSeat(1L, 3, new SeatUpdateRequest(Seat.SeatType.PREFERENTIAL));

        // Then
        assertThat(seat.getSeatType()).isEqualTo(Seat.SeatType.PREFERENTIAL);
        assertThat(result).isSameAs(response);
    }

    @Test
    void shouldUpdateSeat_WithNonExistentSeat_ThrowResourceNotFound() {
        // Given
        when(busRepository.existsById(1L)).thenReturn(true);
        when(seatRepository.findByBusIdAndSeatNumber(1L, 99)).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> busService.updateSeat(1L, 99, new SeatUpdateRequest(Seat.SeatType.PREFERENTIAL)))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Silla 99");
        verify(seatRepository, never()).save(any());
    }

    @Test
    void shouldGetAvailableBuses_WithNullDate_NotQueryTrips() {
        // Given
        when(busRepository.findAll()).thenReturn(List.of(bus));
        when(busMapper.toResponseList(anyList())).thenReturn(List.of(busResponse));

        // When
        List<BusResponse> result = busService.getAvailableBuses(null, null, null);

        // Then
        assertThat(result).hasSize(1);
        verifyNoInteractions(tripRepository);
    }
}
