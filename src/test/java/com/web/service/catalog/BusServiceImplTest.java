package com.web.service.catalog;

import com.web.dto.catalog.Bus.BusCreateRequest;
import com.web.dto.catalog.Bus.BusResponse;
import com.web.dto.catalog.Bus.BusUpdateRequest;
import com.web.dto.catalog.Bus.mapper.BusMapper;
import com.web.entity.Bus;
import com.web.exception.BusinessException;
import com.web.exception.ResourceNotFoundException;
import com.web.repository.BusRepository;
import com.web.repository.TripRepository;
import org.springframework.http.HttpStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
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
    void shouldDeleteBus_WithValidId_DeleteBus() {
        // Given
        when(busRepository.findById(1L)).thenReturn(Optional.of(bus));
        when(busRepository.save(any(Bus.class))).thenReturn(bus);

        // When
        busService.deleteBus(1L);

        // Then
        verify(busRepository).save(argThat(b -> 
            b.getStatus() == Bus.BusStatus.MAINTENANCE
        ));
    }

    @Test
    void shouldGetAvailableBuses_WithDate_ReturnAvailableList() {
        // Given
        List<Bus> buses = List.of(bus);
        List<BusResponse> responses = List.of(busResponse);

        when(busRepository.findAll()).thenReturn(buses);
        when(busMapper.toResponseList(anyList())).thenReturn(responses);

        // When
        List<BusResponse> result = busService.getAvailableBuses(LocalDate.now());

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

        // When
        BusResponse result = busService.getBusByPlate("ABC123");

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

        // Then: el bus pasa a mantenimiento en lugar de borrarse
        assertThat(bus.getStatus()).isEqualTo(Bus.BusStatus.MAINTENANCE);
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
        busService.getAvailableBuses(LocalDate.of(2026, 1, 15));

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
        List<BusResponse> result = busService.getAvailableBuses(LocalDate.of(2026, 1, 15));

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
        busService.getAvailableBuses(date);

        // Then: solo se mapea el bus sin viaje ese día
        verify(busMapper).toResponseList(argThat(list -> list.size() == 1 && list.get(0).getId().equals(bus.getId())));
    }

    @Test
    void shouldGetAvailableBuses_WithNullDate_NotQueryTrips() {
        // Given
        when(busRepository.findAll()).thenReturn(List.of(bus));
        when(busMapper.toResponseList(anyList())).thenReturn(List.of(busResponse));

        // When
        List<BusResponse> result = busService.getAvailableBuses(null);

        // Then
        assertThat(result).hasSize(1);
        verifyNoInteractions(tripRepository);
    }
}
