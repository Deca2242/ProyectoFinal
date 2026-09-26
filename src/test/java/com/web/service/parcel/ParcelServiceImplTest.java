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
        when(parcelMapper.toResponse(any(Parcel.class))).thenReturn(parcelResponse);

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
}

