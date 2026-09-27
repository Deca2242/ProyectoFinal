package com.web.service.parcel;

import java.math.BigDecimal;
import com.web.dto.parcel.ParcelCreateRequest;
import com.web.dto.parcel.ParcelResponse;
import com.web.dto.parcel.mapper.ParcelMapper;
import com.web.entity.Incident;
import com.web.entity.Parcel;
import com.web.entity.Route;
import com.web.entity.Stop;
import com.web.entity.Trip;
import com.web.exception.BusinessException;
import com.web.exception.InvalidSegmentException;
import com.web.exception.InvalidStateTransitionException;
import com.web.exception.ResourceNotFoundException;
import com.web.repository.IncidentRepository;
import com.web.repository.ParcelRepository;
import com.web.repository.StopRepository;
import com.web.repository.TripRepository;
import com.web.util.OtpGenerator;
import com.web.util.QrCodeGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;


@ExtendWith(MockitoExtension.class)
class ParcelServiceImplTest {

    @Mock
    private ParcelRepository parcelRepository;
    @Mock
    private TripRepository tripRepository;
    @Mock
    private StopRepository stopRepository;
    @Mock
    private IncidentRepository incidentRepository;
    @Mock
    private ParcelMapper parcelMapper;
    @Mock
    private QrCodeGenerator qrCodeGenerator;
    @Mock
    private OtpGenerator otpGenerator;

    @InjectMocks
    private ParcelServiceImpl parcelService;

    private Trip trip;
    private Stop fromStop;
    private Stop toStop;
    private Parcel parcel;
    private ParcelResponse parcelResponse;

    @BeforeEach
    void setUp() {
        Route route = Route.builder()
                .id(1L)
                .name("Route Name")
                .build();

        trip = Trip.builder()
                .id(1L)
                .route(route)
                .build();

        fromStop = Stop.builder()
                .id(1L)
                .route(route)
                .name("Origin")
                .order(1)
                .build();

        toStop = Stop.builder()
                .id(2L)
                .route(route)
                .name("Destination")
                .order(2)
                .build();

        parcel = Parcel.builder()
                .id(1L)
                .trip(trip)
                .fromStop(fromStop)
                .toStop(toStop)
                .code("PARCEL001")
                .deliveryOtp("123456")
                .status(Parcel.ParcelStatus.IN_TRANSIT)
                .build();

        parcelResponse = new ParcelResponse(
                1L, "PARCEL001", 1L, "Route Name", null,
                "Sender", "123456789", "Receiver", "987654321",
                1L, "Origin", 2L, "Destination",
                null, null, Parcel.ParcelStatus.IN_TRANSIT,
                "123456", null, null, null
        );
    }

    @Test
    void shouldCreateParcel_WithValidRequest_ReturnParcelResponse() {
        // Given
        ParcelCreateRequest request = new ParcelCreateRequest(
                1L, "Sender", "123456789", "Receiver", "987654321",
                1L, "Origin", 2L, "Destination",
                BigDecimal.valueOf(10000), BigDecimal.valueOf(10.0), "Description"
        );

        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(stopRepository.findById(1L)).thenReturn(Optional.of(fromStop));
        when(stopRepository.findById(2L)).thenReturn(Optional.of(toStop));
        when(qrCodeGenerator.generateParcelCode()).thenReturn("PARCEL001");
        when(otpGenerator.generate6DigitOtp()).thenReturn("123456");
        when(parcelMapper.toEntity(request)).thenReturn(parcel);
        when(parcelRepository.save(any(Parcel.class))).thenAnswer(inv -> {
            Parcel p = inv.getArgument(0);
            p.setId(1L);
            return p;
        });
        when(parcelMapper.toResponseWithOtp(any(Parcel.class))).thenReturn(parcelResponse);

        // When
        ParcelResponse result = parcelService.createParcel(request);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.id()).isEqualTo(1L);
        verify(tripRepository).findById(1L);
        verify(parcelRepository).save(any(Parcel.class));
    }

    @Test
    void shouldTrackParcel_WithValidCode_ReturnParcelResponse() {
        // Given
        when(parcelRepository.findByCode("PARCEL001")).thenReturn(Optional.of(parcel));
        when(parcelMapper.toPublicResponse(parcel)).thenReturn(parcelResponse);

        // When
        ParcelResponse result = parcelService.trackParcel("PARCEL001");

        // Then
        assertThat(result).isNotNull();
        assertThat(result.code()).isEqualTo("PARCEL001");
        verify(parcelRepository).findByCode("PARCEL001");
        // El rastreo público nunca usa el mapeo que incluye el OTP
        verify(parcelMapper, never()).toResponse(any(Parcel.class));
    }

    @Test
    void shouldDeliverWithOtp_WithValidOtp_ReturnDeliveredParcel() {
        // Given
        when(parcelRepository.findById(1L)).thenReturn(Optional.of(parcel));
        when(otpGenerator.validateOtp("123456", "123456")).thenReturn(true);
        when(parcelRepository.save(any(Parcel.class))).thenReturn(parcel);
        when(parcelMapper.toResponse(any(Parcel.class))).thenReturn(parcelResponse);

        // When
        ParcelResponse result = parcelService.deliverWithOtp(1L, "123456", "photo.jpg");

        // Then
        assertThat(result).isNotNull();
        verify(parcelRepository).save(argThat(p -> 
            p.getStatus() == Parcel.ParcelStatus.DELIVERED
        ));
    }

    @Test
    void shouldDeliverWithOtp_WithInvalidOtp_CreateIncident() {
        // Given
        when(parcelRepository.findById(1L)).thenReturn(Optional.of(parcel));
        when(otpGenerator.validateOtp("000000", "123456")).thenReturn(false);
        when(parcelRepository.save(any(Parcel.class))).thenReturn(parcel);
        when(incidentRepository.save(any(Incident.class))).thenAnswer(inv -> {
            Incident i = inv.getArgument(0);
            return i;
        });

        // When/Then
        assertThatThrownBy(() -> parcelService.deliverWithOtp(1L, "000000", "photo.jpg"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("OTP inválido");

        verify(parcelRepository).save(argThat(p -> 
            p.getStatus() == Parcel.ParcelStatus.FAILED
        ));
        verify(incidentRepository).save(any(Incident.class));
    }

    @Test
    void shouldUpdateStatus_WithValidStatus_UpdateParcel() {
        // Given
        parcel.setStatus(Parcel.ParcelStatus.CREATED);
        when(parcelRepository.findById(1L)).thenReturn(Optional.of(parcel));
        when(parcelRepository.save(any(Parcel.class))).thenReturn(parcel);
        when(parcelMapper.toResponse(any(Parcel.class))).thenReturn(parcelResponse);

        // When
        ParcelResponse result = parcelService.updateStatus(1L, Parcel.ParcelStatus.IN_TRANSIT);

        // Then
        assertThat(result).isNotNull();
        verify(parcelRepository).save(argThat(p ->
            p.getStatus() == Parcel.ParcelStatus.IN_TRANSIT
        ));
    }

    @Test
    void shouldUpdateStatus_ToDeliveredWithoutOtp_ThrowException() {
        // Given
        when(parcelRepository.findById(1L)).thenReturn(Optional.of(parcel));

        // When/Then: DELIVERED solo se alcanza con deliverWithOtp
        assertThatThrownBy(() -> parcelService.updateStatus(1L, Parcel.ParcelStatus.DELIVERED))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("OTP");
        verify(parcelRepository, never()).save(any());
    }

    @Test
    void shouldGetParcelsInTransit_WithValidTripId_ReturnList() {
        // Given
        List<Parcel> parcels = List.of(parcel);
        List<ParcelResponse> responses = List.of(parcelResponse);

        when(parcelRepository.findByTripIdAndStatus(1L, Parcel.ParcelStatus.IN_TRANSIT))
                .thenReturn(parcels);
        when(parcelMapper.toResponseList(parcels)).thenReturn(responses);

        // When
        List<ParcelResponse> result = parcelService.getParcelsInTransit(1L);

        // Then
        assertThat(result).isNotNull();
        assertThat(result).hasSize(1);
        verify(parcelRepository).findByTripIdAndStatus(1L, Parcel.ParcelStatus.IN_TRANSIT);
    }

    // ==================== getAllParcels ====================

    @Test
    void shouldGetAllParcels_WithParcels_MapEachOne() {
        // Given
        Parcel other = Parcel.builder().id(2L).code("PARCEL002").build();
        ParcelResponse otherResponse = new ParcelResponse(
                2L, "PARCEL002", 1L, "Route Name", null,
                "Sender", "1", "Receiver", "2",
                1L, "Origin", 2L, "Destination",
                null, null, Parcel.ParcelStatus.CREATED,
                "654321", null, null, null
        );
        when(parcelRepository.findAll()).thenReturn(List.of(parcel, other));
        when(parcelMapper.toResponse(parcel)).thenReturn(parcelResponse);
        when(parcelMapper.toResponse(other)).thenReturn(otherResponse);

        // When
        List<ParcelResponse> result = parcelService.getAllParcels();

        // Then
        assertThat(result).containsExactly(parcelResponse, otherResponse);
    }

    @Test
    void shouldGetAllParcels_WithoutParcels_ReturnEmptyList() {
        // Given
        when(parcelRepository.findAll()).thenReturn(List.of());

        // When
        List<ParcelResponse> result = parcelService.getAllParcels();

        // Then
        assertThat(result).isEmpty();
        verifyNoInteractions(parcelMapper);
    }

    // ==================== createParcel ====================

    @Test
    void shouldCreateParcel_WithNonExistentTrip_ThrowResourceNotFound() {
        // Given
        ParcelCreateRequest request = buildParcelRequest();
        when(tripRepository.findById(1L)).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> parcelService.createParcel(request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Viaje");
        verifyNoInteractions(stopRepository, parcelRepository);
    }

    @Test
    void shouldCreateParcel_WithNonExistentFromStop_ThrowResourceNotFound() {
        // Given
        ParcelCreateRequest request = buildParcelRequest();
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(stopRepository.findById(1L)).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> parcelService.createParcel(request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Parada origen");
        verifyNoInteractions(parcelRepository);
    }

    @Test
    void shouldCreateParcel_WithNonExistentToStop_ThrowResourceNotFound() {
        // Given
        ParcelCreateRequest request = buildParcelRequest();
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(stopRepository.findById(1L)).thenReturn(Optional.of(fromStop));
        when(stopRepository.findById(2L)).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> parcelService.createParcel(request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Parada destino");
        verifyNoInteractions(parcelRepository);
    }

    @ParameterizedTest
    @CsvSource({"99, 1", "1, 99"})
    void shouldCreateParcel_WithStopsFromAnotherRoute_ThrowInvalidSegment(long fromRouteId, long toRouteId) {
        // Given
        fromStop.setRoute(Route.builder().id(fromRouteId).build());
        toStop.setRoute(Route.builder().id(toRouteId).build());
        ParcelCreateRequest request = buildParcelRequest();
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(stopRepository.findById(1L)).thenReturn(Optional.of(fromStop));
        when(stopRepository.findById(2L)).thenReturn(Optional.of(toStop));

        // When/Then
        assertThatThrownBy(() -> parcelService.createParcel(request))
                .isInstanceOf(InvalidSegmentException.class)
                .hasMessageContaining("no pertenecen a la ruta");
        verifyNoInteractions(qrCodeGenerator, otpGenerator, parcelRepository);
    }

    @ParameterizedTest
    @CsvSource({"2, 2", "3, 1"})
    void shouldCreateParcel_WithOriginNotBeforeDestination_ThrowInvalidSegment(int fromOrder, int toOrder) {
        // Given
        fromStop.setOrder(fromOrder);
        toStop.setOrder(toOrder);
        ParcelCreateRequest request = buildParcelRequest();
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(stopRepository.findById(1L)).thenReturn(Optional.of(fromStop));
        when(stopRepository.findById(2L)).thenReturn(Optional.of(toStop));

        // When/Then
        assertThatThrownBy(() -> parcelService.createParcel(request))
                .isInstanceOf(InvalidSegmentException.class)
                .hasMessageContaining("anterior a la de destino")
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo("INVALID_SEGMENT"));
        verifyNoInteractions(qrCodeGenerator, otpGenerator, parcelRepository);
    }

    @Test
    void shouldCreateParcel_WithValidRequest_SetRelationsCodeAndOtp() {
        // Given: el mapper devuelve una entidad sin relaciones ni código
        ParcelCreateRequest request = buildParcelRequest();
        Parcel mapped = Parcel.builder()
                .senderName("Sender")
                .receiverName("Receiver")
                .price(BigDecimal.valueOf(10000))
                .build();
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(stopRepository.findById(1L)).thenReturn(Optional.of(fromStop));
        when(stopRepository.findById(2L)).thenReturn(Optional.of(toStop));
        when(qrCodeGenerator.generateParcelCode()).thenReturn("PCL-XYZ");
        when(otpGenerator.generate6DigitOtp()).thenReturn("987654");
        when(parcelMapper.toEntity(request)).thenReturn(mapped);
        when(parcelRepository.save(any(Parcel.class))).thenAnswer(inv -> inv.getArgument(0));
        when(parcelMapper.toResponseWithOtp(any(Parcel.class))).thenReturn(parcelResponse);

        // When
        parcelService.createParcel(request);

        // Then
        ArgumentCaptor<Parcel> captor = ArgumentCaptor.forClass(Parcel.class);
        verify(parcelRepository).save(captor.capture());
        Parcel saved = captor.getValue();
        assertThat(saved.getTrip()).isSameAs(trip);
        assertThat(saved.getFromStop()).isSameAs(fromStop);
        assertThat(saved.getToStop()).isSameAs(toStop);
        assertThat(saved.getCode()).isEqualTo("PCL-XYZ");
        assertThat(saved.getDeliveryOtp()).isEqualTo("987654");
        assertThat(saved.getStatus()).isEqualTo(Parcel.ParcelStatus.CREATED);
    }

    // ==================== trackParcel ====================

    @Test
    void shouldTrackParcel_WithUnknownCode_ThrowResourceNotFound() {
        // Given
        when(parcelRepository.findByCode("NOPE")).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> parcelService.trackParcel("NOPE"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("NOPE")
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        verifyNoInteractions(parcelMapper);
    }

    // ==================== updateStatus ====================

    @Test
    void shouldUpdateStatus_WithNonExistentParcel_ThrowResourceNotFound() {
        // Given
        when(parcelRepository.findById(99L)).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> parcelService.updateStatus(99L, Parcel.ParcelStatus.IN_TRANSIT))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Encomienda");
        verify(parcelRepository, never()).save(any());
    }

    @ParameterizedTest
    @CsvSource({
            "CREATED, IN_TRANSIT",
            "IN_TRANSIT, FAILED"
    })
    void shouldUpdateStatus_WithAllowedTransition_SaveNewStatus(Parcel.ParcelStatus current,
                                                               Parcel.ParcelStatus target) {
        // Given
        parcel.setStatus(current);
        when(parcelRepository.findById(1L)).thenReturn(Optional.of(parcel));
        when(parcelRepository.save(any(Parcel.class))).thenAnswer(inv -> inv.getArgument(0));
        when(parcelMapper.toResponse(any(Parcel.class))).thenReturn(parcelResponse);

        // When
        ParcelResponse result = parcelService.updateStatus(1L, target);

        // Then
        assertThat(result).isEqualTo(parcelResponse);
        verify(parcelRepository).save(argThat(p -> p.getStatus() == target));
    }

    @ParameterizedTest
    @CsvSource({
            "CREATED, CREATED",
            "CREATED, FAILED",
            "IN_TRANSIT, CREATED",
            "IN_TRANSIT, IN_TRANSIT",
            "FAILED, CREATED",
            "FAILED, IN_TRANSIT",
            "FAILED, FAILED",
            "DELIVERED, CREATED",
            "DELIVERED, IN_TRANSIT",
            "DELIVERED, FAILED"
    })
    void shouldUpdateStatus_WithForbiddenTransition_ThrowInvalidStateTransition(Parcel.ParcelStatus current,
                                                                                Parcel.ParcelStatus target) {
        // Given
        parcel.setStatus(current);
        when(parcelRepository.findById(1L)).thenReturn(Optional.of(parcel));

        // When/Then
        assertThatThrownBy(() -> parcelService.updateStatus(1L, target))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining(current.name())
                .hasMessageContaining(target.name())
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo("INVALID_STATE_TRANSITION");
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                });
        assertThat(parcel.getStatus()).isEqualTo(current);
        verify(parcelRepository, never()).save(any());
    }

    @ParameterizedTest
    @EnumSource(Parcel.ParcelStatus.class)
    void shouldUpdateStatus_ToDeliveredFromAnyStatus_RequireOtp(Parcel.ParcelStatus current) {
        // Given
        parcel.setStatus(current);
        when(parcelRepository.findById(1L)).thenReturn(Optional.of(parcel));

        // When/Then: DELIVERED nunca se alcanza por updateStatus, ni siquiera desde IN_TRANSIT
        assertThatThrownBy(() -> parcelService.updateStatus(1L, Parcel.ParcelStatus.DELIVERED))
                .isInstanceOf(BusinessException.class)
                .isNotInstanceOf(InvalidStateTransitionException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo("DELIVERY_REQUIRES_OTP");
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
        assertThat(parcel.getStatus()).isEqualTo(current);
        verify(parcelRepository, never()).save(any());
    }

    // ==================== deliverWithOtp ====================

    @Test
    void shouldDeliverWithOtp_WithNonExistentParcel_ThrowResourceNotFound() {
        // Given
        when(parcelRepository.findById(99L)).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> parcelService.deliverWithOtp(99L, "123456", "photo.jpg"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Encomienda");
        verifyNoInteractions(otpGenerator, incidentRepository);
    }

    @ParameterizedTest
    @EnumSource(value = Parcel.ParcelStatus.class, names = {"CREATED", "FAILED", "DELIVERED"})
    void shouldDeliverWithOtp_WithParcelNotInTransit_ThrowInvalidParcelStatus(Parcel.ParcelStatus status) {
        // Given
        parcel.setStatus(status);
        when(parcelRepository.findById(1L)).thenReturn(Optional.of(parcel));

        // When/Then
        assertThatThrownBy(() -> parcelService.deliverWithOtp(1L, "123456", "photo.jpg"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo("INVALID_PARCEL_STATUS");
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
        assertThat(parcel.getStatus()).isEqualTo(status);
        verifyNoInteractions(otpGenerator, incidentRepository);
        verify(parcelRepository, never()).save(any());
    }

    @Test
    void shouldDeliverWithOtp_WithValidOtp_SetProofPhotoAndDeliveryTime() {
        // Given
        LocalDateTime before = LocalDateTime.now();
        when(parcelRepository.findById(1L)).thenReturn(Optional.of(parcel));
        when(otpGenerator.validateOtp("123456", "123456")).thenReturn(true);
        when(parcelRepository.save(any(Parcel.class))).thenAnswer(inv -> inv.getArgument(0));
        when(parcelMapper.toResponse(any(Parcel.class))).thenReturn(parcelResponse);

        // When
        parcelService.deliverWithOtp(1L, "123456", "https://fotos/prueba.jpg");

        // Then
        ArgumentCaptor<Parcel> captor = ArgumentCaptor.forClass(Parcel.class);
        verify(parcelRepository).save(captor.capture());
        Parcel saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(Parcel.ParcelStatus.DELIVERED);
        assertThat(saved.getProofPhotoUrl()).isEqualTo("https://fotos/prueba.jpg");
        assertThat(saved.getDeliveredAt()).isNotNull().isAfterOrEqualTo(before);
        verifyNoInteractions(incidentRepository);
    }

    @Test
    void shouldDeliverWithOtp_WithInvalidOtp_CreateIncidentWithoutLeakingOtp() {
        // Given
        when(parcelRepository.findById(1L)).thenReturn(Optional.of(parcel));
        when(otpGenerator.validateOtp("000000", "123456")).thenReturn(false);
        when(parcelRepository.save(any(Parcel.class))).thenAnswer(inv -> inv.getArgument(0));
        when(incidentRepository.save(any(Incident.class))).thenAnswer(inv -> inv.getArgument(0));

        // When/Then
        assertThatThrownBy(() -> parcelService.deliverWithOtp(1L, "000000", "photo.jpg"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo("INVALID_OTP");
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                });

        ArgumentCaptor<Incident> captor = ArgumentCaptor.forClass(Incident.class);
        verify(incidentRepository).save(captor.capture());
        Incident incident = captor.getValue();
        assertThat(incident.getEntityType()).isEqualTo(Incident.EntityType.PARCEL);
        assertThat(incident.getEntityId()).isEqualTo(1L);
        assertThat(incident.getIncidentType()).isEqualTo(Incident.IncidentType.DELIVERY_FAIL);
        assertThat(incident.getDescription()).contains("PARCEL001").doesNotContain("123456");
        assertThat(incident.getCreatedAt()).isNotNull();
        assertThat(parcel.getProofPhotoUrl()).isNull();
        assertThat(parcel.getDeliveredAt()).isNull();
        verifyNoInteractions(parcelMapper);
    }

    // ==================== getParcelsByDateRange ====================

    @Test
    void shouldGetParcelsByDateRange_WithRange_DelegateToRepository() {
        // Given
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate end = LocalDate.of(2026, 1, 31);
        List<Parcel> parcels = List.of(parcel);
        when(parcelRepository.findByDateRange(start, end)).thenReturn(parcels);
        when(parcelMapper.toResponseList(parcels)).thenReturn(List.of(parcelResponse));

        // When
        List<ParcelResponse> result = parcelService.getParcelsByDateRange(start, end);

        // Then
        assertThat(result).containsExactly(parcelResponse);
    }

    private ParcelCreateRequest buildParcelRequest() {
        return new ParcelCreateRequest(
                1L, "Sender", "123456789", "Receiver", "987654321",
                1L, "Origin", 2L, "Destination",
                BigDecimal.valueOf(10000), BigDecimal.valueOf(10.0), "Description"
        );
    }
}

