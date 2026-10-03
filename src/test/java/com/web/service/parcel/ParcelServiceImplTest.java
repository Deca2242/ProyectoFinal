package com.web.service.parcel;

import java.math.BigDecimal;
import com.web.dto.parcel.ParcelCreateRequest;
import com.web.dto.parcel.ParcelResponse;
import com.web.dto.parcel.mapper.ParcelMapper;
import com.web.entity.Assignment;
import com.web.entity.Incident;
import com.web.entity.Parcel;
import com.web.entity.Route;
import com.web.entity.Stop;
import com.web.entity.Trip;
import com.web.entity.User;
import com.web.exception.BusinessException;
import com.web.exception.InvalidSegmentException;
import com.web.exception.InvalidStateTransitionException;
import com.web.exception.ResourceNotFoundException;
import com.web.repository.AssignmentRepository;
import com.web.repository.IncidentRepository;
import com.web.repository.ParcelRepository;
import com.web.repository.StopRepository;
import com.web.repository.TripRepository;
import com.web.repository.UserRepository;
import com.web.service.admin.ConfigService;
import com.web.service.notification.NotificationService;
import com.web.util.OtpGenerator;
import com.web.util.QrCodeGenerator;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

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

    private static final String HASH = "hash-de-123456";

    @Mock
    private ParcelRepository parcelRepository;
    @Mock
    private TripRepository tripRepository;
    @Mock
    private StopRepository stopRepository;
    @Mock
    private IncidentRepository incidentRepository;
    @Mock
    private AssignmentRepository assignmentRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private ParcelMapper parcelMapper;
    @Mock
    private QrCodeGenerator qrCodeGenerator;
    @Mock
    private OtpGenerator otpGenerator;
    @Mock
    private ConfigService configService;
    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private ParcelServiceImpl parcelService;

    private Route route;
    private Trip trip;
    private Stop fromStop;
    private Stop toStop;
    private Parcel parcel;
    private ParcelResponse parcelResponse;

    @BeforeEach
    void setUp() {
        route = Route.builder()
                .id(1L)
                .name("Route Name")
                .build();

        trip = Trip.builder()
                .id(1L)
                .route(route)
                .status(Trip.TripStatus.BOARDING)
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
                .deliveryOtp(HASH)
                .status(Parcel.ParcelStatus.IN_TRANSIT)
                .build();

        parcelResponse = new ParcelResponse(
                1L, "PARCEL001", 1L, "Route Name", null,
                "Sender", "123456789", "Receiver", "987654321",
                1L, "Origin", 2L, "Destination",
                null, null, Parcel.ParcelStatus.IN_TRANSIT,
                null, null, null, null, "Description", 0
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate(String username, String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                username, null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    // ==================== createParcel ====================

    @Test
    void shouldCreateParcel_WithValidRequest_SaveOtpHashAndReturnPlainOtpOnce() {
        // Given: el mapper devuelve una entidad sin relaciones ni código
        ParcelCreateRequest request = buildParcelRequest();
        Parcel mapped = Parcel.builder()
                .senderName("Sender")
                .receiverName("Receiver")
                .description("Description")
                .price(BigDecimal.valueOf(10000))
                .build();
        ParcelResponse withOtp = new ParcelResponse(1L, "PCL-XYZ", 1L, "Route Name", null,
                "Sender", "123456789", "Receiver", "987654321", 1L, "Origin", 2L, "Destination",
                null, null, Parcel.ParcelStatus.CREATED, "987654", null, null, null, "Description", 0);
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(stopRepository.findById(1L)).thenReturn(Optional.of(fromStop));
        when(stopRepository.findById(2L)).thenReturn(Optional.of(toStop));
        when(qrCodeGenerator.generateParcelCode()).thenReturn("PCL-XYZ");
        when(otpGenerator.generate6DigitOtp()).thenReturn("987654");
        when(otpGenerator.hashOtp("PCL-XYZ", "987654")).thenReturn("hash-987654");
        when(parcelMapper.toEntity(request)).thenReturn(mapped);
        when(parcelRepository.save(any(Parcel.class))).thenAnswer(inv -> inv.getArgument(0));
        when(parcelMapper.toResponseWithOtp(mapped, "987654")).thenReturn(withOtp);

        // When
        ParcelResponse result = parcelService.createParcel(request);

        // Then: se guarda el hash, nunca el OTP en claro
        ArgumentCaptor<Parcel> captor = ArgumentCaptor.forClass(Parcel.class);
        verify(parcelRepository).save(captor.capture());
        Parcel saved = captor.getValue();
        assertThat(saved.getTrip()).isSameAs(trip);
        assertThat(saved.getFromStop()).isSameAs(fromStop);
        assertThat(saved.getToStop()).isSameAs(toStop);
        assertThat(saved.getCode()).isEqualTo("PCL-XYZ");
        assertThat(saved.getDeliveryOtp()).isEqualTo("hash-987654").isNotEqualTo("987654");
        assertThat(saved.getOtpAttempts()).isZero();
        assertThat(saved.getDescription()).isEqualTo("Description");
        assertThat(saved.getStatus()).isEqualTo(Parcel.ParcelStatus.CREATED);
        // La taquilla recibe el OTP en claro y la notificación mock se envía con él
        assertThat(result.deliveryOtp()).isEqualTo("987654");
        verify(notificationService).notifyParcelCreated(saved, "987654");
        verify(parcelMapper, never()).toResponse(any(Parcel.class));
        verify(parcelMapper, never()).toPublicResponse(any(Parcel.class));
    }

    @Test
    void shouldCreateParcel_WithNonExistentTrip_ThrowResourceNotFound() {
        // Given
        ParcelCreateRequest request = buildParcelRequest();
        when(tripRepository.findById(1L)).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> parcelService.createParcel(request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Viaje");
        verifyNoInteractions(stopRepository, parcelRepository, notificationService);
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

    @ParameterizedTest
    @EnumSource(value = Trip.TripStatus.class, names = {"DEPARTED", "ARRIVED", "CANCELLED"})
    void shouldCreateParcel_WithTripAlreadyDepartedOrCancelled_ThrowTripNotAvailable(Trip.TripStatus status) {
        // Given
        trip.setStatus(status);
        ParcelCreateRequest request = buildParcelRequest();
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));

        // When/Then
        assertThatThrownBy(() -> parcelService.createParcel(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(status.name())
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(be.getCode()).isEqualTo("TRIP_NOT_AVAILABLE");
                });
        verifyNoInteractions(stopRepository, qrCodeGenerator, otpGenerator, parcelMapper, notificationService);
        verify(parcelRepository, never()).save(any());
    }

    // ==================== trackParcel ====================

    @Test
    void shouldTrackParcel_WithValidCode_UsePublicResponse() {
        // Given
        when(parcelRepository.findByCode("PARCEL001")).thenReturn(Optional.of(parcel));
        when(parcelMapper.toPublicResponse(parcel)).thenReturn(parcelResponse);

        // When
        ParcelResponse result = parcelService.trackParcel("PARCEL001");

        // Then: el rastreo público nunca usa el mapeo del personal
        assertThat(result.code()).isEqualTo("PARCEL001");
        verify(parcelMapper, never()).toResponse(any(Parcel.class));
    }

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

    // ==================== searchParcels ====================

    @Test
    @SuppressWarnings("unchecked")
    void shouldSearchParcels_WithFilters_QueryOrderedByCreationDesc() {
        // Given
        when(parcelRepository.findAll(any(Specification.class), any(Sort.class))).thenReturn(List.of(parcel));
        when(parcelMapper.toResponseList(List.of(parcel))).thenReturn(List.of(parcelResponse));

        // When
        List<ParcelResponse> result = parcelService.searchParcels(LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 1, 31), Parcel.ParcelStatus.IN_TRANSIT);

        // Then
        assertThat(result).containsExactly(parcelResponse);
        ArgumentCaptor<Sort> sort = ArgumentCaptor.forClass(Sort.class);
        verify(parcelRepository).findAll(any(Specification.class), sort.capture());
        assertThat(sort.getValue().getOrderFor("createdAt").getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    void shouldSearchParcels_WithFromAfterTo_ThrowBadRequest() {
        // When/Then
        assertThatThrownBy(() -> parcelService.searchParcels(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 1, 1), null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(be.getCode()).isEqualTo("INVALID_DATE_RANGE");
                });
        verifyNoInteractions(parcelRepository);
    }

    // ==================== updateStatus ====================

    @Test
    void shouldUpdateStatus_WithNonExistentParcel_ThrowResourceNotFound() {
        // Given
        when(parcelRepository.findByCode("NOPE")).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> parcelService.updateStatus("NOPE", Parcel.ParcelStatus.IN_TRANSIT, null, null))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Encomienda");
        verify(parcelRepository, never()).save(any());
    }

    @ParameterizedTest
    @EnumSource(value = Trip.TripStatus.class, names = {"BOARDING", "DEPARTED"})
    void shouldUpdateStatus_ToInTransitWithTripOnTheRoad_SaveNewStatus(Trip.TripStatus tripStatus) {
        // Given
        trip.setStatus(tripStatus);
        parcel.setStatus(Parcel.ParcelStatus.CREATED);
        when(parcelRepository.findByCode("PARCEL001")).thenReturn(Optional.of(parcel));
        when(parcelRepository.save(parcel)).thenReturn(parcel);
        when(parcelMapper.toResponse(parcel)).thenReturn(parcelResponse);

        // When
        ParcelResponse result = parcelService.updateStatus("PARCEL001", Parcel.ParcelStatus.IN_TRANSIT, null, null);

        // Then
        assertThat(result).isEqualTo(parcelResponse);
        assertThat(parcel.getStatus()).isEqualTo(Parcel.ParcelStatus.IN_TRANSIT);
        verify(parcelMapper, never()).toResponseWithOtp(any(Parcel.class), any());
        verifyNoInteractions(incidentRepository);
    }

    @ParameterizedTest
    @EnumSource(value = Trip.TripStatus.class, names = {"SCHEDULED", "ARRIVED", "CANCELLED"})
    void shouldUpdateStatus_ToInTransitWithTripNotOnTheRoad_ThrowInvalidStateTransition(Trip.TripStatus tripStatus) {
        // Given
        trip.setStatus(tripStatus);
        parcel.setStatus(Parcel.ParcelStatus.CREATED);
        when(parcelRepository.findByCode("PARCEL001")).thenReturn(Optional.of(parcel));

        // When/Then
        assertThatThrownBy(() -> parcelService.updateStatus("PARCEL001", Parcel.ParcelStatus.IN_TRANSIT, null, null))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining(tripStatus.name())
                .extracting("status").isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(parcel.getStatus()).isEqualTo(Parcel.ParcelStatus.CREATED);
        verify(parcelRepository, never()).save(any());
    }

    @Test
    void shouldUpdateStatus_ToInTransitByUnassignedDriver_ThrowForbidden() {
        // Given
        authenticate("otro@test.com", "DRIVER");
        parcel.setStatus(Parcel.ParcelStatus.CREATED);
        when(parcelRepository.findByCode("PARCEL001")).thenReturn(Optional.of(parcel));
        when(assignmentRepository.findByTripId(1L)).thenReturn(Optional.of(Assignment.builder()
                .driver(User.builder().email("driver@test.com").build()).build()));

        // When/Then
        assertThatThrownBy(() -> parcelService.updateStatus("PARCEL001", Parcel.ParcelStatus.IN_TRANSIT, null, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(be.getCode()).isEqualTo("DRIVER_NOT_ASSIGNED");
                });
        verify(parcelRepository, never()).save(any());
    }

    @Test
    void shouldUpdateStatus_ToInTransitByAssignedDriver_SaveNewStatus() {
        // Given
        authenticate("driver@test.com", "DRIVER");
        parcel.setStatus(Parcel.ParcelStatus.CREATED);
        when(parcelRepository.findByCode("PARCEL001")).thenReturn(Optional.of(parcel));
        when(assignmentRepository.findByTripId(1L)).thenReturn(Optional.of(Assignment.builder()
                .driver(User.builder().email("driver@test.com").build()).build()));
        when(parcelRepository.save(parcel)).thenReturn(parcel);
        when(parcelMapper.toResponse(parcel)).thenReturn(parcelResponse);

        // When
        parcelService.updateStatus("PARCEL001", Parcel.ParcelStatus.IN_TRANSIT, null, null);

        // Then
        assertThat(parcel.getStatus()).isEqualTo(Parcel.ParcelStatus.IN_TRANSIT);
    }

    @Test
    void shouldUpdateStatus_ToFailedManually_CreateIncidentReportedByCurrentUser() {
        // Given
        authenticate("clerk@test.com", "CLERK");
        User clerk = User.builder().id(8L).email("clerk@test.com").build();
        when(parcelRepository.findByCode("PARCEL001")).thenReturn(Optional.of(parcel));
        when(parcelRepository.save(parcel)).thenReturn(parcel);
        when(userRepository.findByEmail("clerk@test.com")).thenReturn(Optional.of(clerk));
        when(parcelMapper.toResponse(parcel)).thenReturn(parcelResponse);

        // When
        parcelService.updateStatus("PARCEL001", Parcel.ParcelStatus.FAILED, null, null);

        // Then
        assertThat(parcel.getStatus()).isEqualTo(Parcel.ParcelStatus.FAILED);
        ArgumentCaptor<Incident> captor = ArgumentCaptor.forClass(Incident.class);
        verify(incidentRepository).save(captor.capture());
        Incident incident = captor.getValue();
        assertThat(incident.getEntityType()).isEqualTo(Incident.EntityType.PARCEL);
        assertThat(incident.getEntityId()).isEqualTo(1L);
        assertThat(incident.getIncidentType()).isEqualTo(Incident.IncidentType.DELIVERY_FAIL);
        assertThat(incident.getReportedBy()).isSameAs(clerk);
        assertThat(incident.getDescription()).contains("PARCEL001");
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
        when(parcelRepository.findByCode("PARCEL001")).thenReturn(Optional.of(parcel));

        // When/Then: FAILED → IN_TRANSIT solo con la reapertura de DISPATCHER/ADMIN
        assertThatThrownBy(() -> parcelService.updateStatus("PARCEL001", target, null, null))
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
        verifyNoInteractions(incidentRepository);
    }

    @ParameterizedTest
    @CsvSource(value = {"NULL, photo.jpg", "123456, NULL", "' ', photo.jpg", "123456, ' '"}, nullValues = "NULL")
    void shouldUpdateStatus_ToDeliveredWithoutOtpOrPhoto_ThrowDeliveryRequiresOtp(String otp, String photo) {
        // When/Then
        assertThatThrownBy(() -> parcelService.updateStatus("PARCEL001", Parcel.ParcelStatus.DELIVERED, otp, photo))
                .isInstanceOf(BusinessException.class)
                .isNotInstanceOf(InvalidStateTransitionException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo("DELIVERY_REQUIRES_OTP");
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
        verifyNoInteractions(parcelRepository, otpGenerator);
    }

    @Test
    void shouldUpdateStatus_ToDeliveredWithOtpAndPhoto_DelegateToOtpDelivery() {
        // Given
        when(parcelRepository.findByCode("PARCEL001")).thenReturn(Optional.of(parcel));
        when(otpGenerator.matchesOtp("PARCEL001", "123456", HASH)).thenReturn(true);
        when(parcelRepository.save(parcel)).thenReturn(parcel);
        when(parcelMapper.toResponse(parcel)).thenReturn(parcelResponse);

        // When
        parcelService.updateStatus("PARCEL001", Parcel.ParcelStatus.DELIVERED, "123456", "https://foto");

        // Then: mismo resultado que /deliver
        assertThat(parcel.getStatus()).isEqualTo(Parcel.ParcelStatus.DELIVERED);
        assertThat(parcel.getProofPhotoUrl()).isEqualTo("https://foto");
        assertThat(parcel.getDeliveredAt()).isNotNull();
        verifyNoInteractions(incidentRepository);
    }

    // ==================== deliverWithOtp ====================

    @Test
    void shouldDeliverWithOtp_WithNonExistentParcel_ThrowResourceNotFound() {
        // Given
        when(parcelRepository.findByCode("NOPE")).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> parcelService.deliverWithOtp("NOPE", "123456", "photo.jpg"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Encomienda");
        verifyNoInteractions(otpGenerator, incidentRepository);
    }

    @ParameterizedTest
    @EnumSource(value = Parcel.ParcelStatus.class, names = {"CREATED", "FAILED", "DELIVERED"})
    void shouldDeliverWithOtp_WithParcelNotInTransit_ThrowInvalidStateTransition(Parcel.ParcelStatus status) {
        // Given
        parcel.setStatus(status);
        when(parcelRepository.findByCode("PARCEL001")).thenReturn(Optional.of(parcel));

        // When/Then: estado inválido para la transición → 422 (no 400)
        assertThatThrownBy(() -> parcelService.deliverWithOtp("PARCEL001", "123456", "photo.jpg"))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining(status.name())
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo("INVALID_STATE_TRANSITION");
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                });
        assertThat(parcel.getStatus()).isEqualTo(status);
        verifyNoInteractions(otpGenerator, incidentRepository);
        verify(parcelRepository, never()).save(any());
    }

    @Test
    void shouldDeliverWithOtp_WithValidOtp_SetProofPhotoAndDeliveryTime() {
        // Given
        LocalDateTime before = LocalDateTime.now();
        when(parcelRepository.findByCode("PARCEL001")).thenReturn(Optional.of(parcel));
        when(otpGenerator.matchesOtp("PARCEL001", "123456", HASH)).thenReturn(true);
        when(parcelRepository.save(any(Parcel.class))).thenAnswer(inv -> inv.getArgument(0));
        when(parcelMapper.toResponse(parcel)).thenReturn(parcelResponse);

        // When
        parcelService.deliverWithOtp("PARCEL001", "123456", "https://fotos/prueba.jpg");

        // Then
        ArgumentCaptor<Parcel> captor = ArgumentCaptor.forClass(Parcel.class);
        verify(parcelRepository).save(captor.capture());
        Parcel saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(Parcel.ParcelStatus.DELIVERED);
        assertThat(saved.getProofPhotoUrl()).isEqualTo("https://fotos/prueba.jpg");
        assertThat(saved.getDeliveredAt()).isNotNull().isAfterOrEqualTo(before);
        verifyNoInteractions(incidentRepository);
        verify(parcelMapper, never()).toResponseWithOtp(any(Parcel.class), any());
    }

    @Test
    void shouldDeliverWithOtp_WithoutPhoto_ThrowBadRequest() {
        // Given
        when(parcelRepository.findByCode("PARCEL001")).thenReturn(Optional.of(parcel));

        // When/Then
        assertThatThrownBy(() -> parcelService.deliverWithOtp("PARCEL001", "123456", " "))
                .isInstanceOf(BusinessException.class)
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(otpGenerator, incidentRepository);
    }

    @Test
    void shouldDeliverWithOtp_WithInvalidOtpBeforeMaxAttempts_CountAttemptWithoutFailing() {
        // Given: máximo de 3 intentos y ninguno usado
        when(parcelRepository.findByCode("PARCEL001")).thenReturn(Optional.of(parcel));
        when(otpGenerator.matchesOtp("PARCEL001", "000000", HASH)).thenReturn(false);
        when(configService.getParcelOtpMaxAttempts()).thenReturn(3);

        // When/Then: 400 con los intentos restantes
        assertThatThrownBy(() -> parcelService.deliverWithOtp("PARCEL001", "000000", "photo.jpg"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Intentos restantes: 2")
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo("INVALID_OTP");
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                });

        assertThat(parcel.getOtpAttempts()).isEqualTo(1);
        assertThat(parcel.getStatus()).isEqualTo(Parcel.ParcelStatus.IN_TRANSIT);
        verify(parcelRepository).save(parcel);
        verifyNoInteractions(incidentRepository);
    }

    @Test
    void shouldDeliverWithOtp_WithInvalidOtpReachingMaxAttempts_FailAndCreateIncidentWithoutLeakingOtp() {
        // Given: ya lleva 2 intentos fallidos de 3
        authenticate("driver@test.com", "DRIVER");
        User driver = User.builder().id(5L).email("driver@test.com").build();
        parcel.setOtpAttempts(2);
        when(parcelRepository.findByCode("PARCEL001")).thenReturn(Optional.of(parcel));
        when(otpGenerator.matchesOtp("PARCEL001", "000000", HASH)).thenReturn(false);
        when(configService.getParcelOtpMaxAttempts()).thenReturn(3);
        when(userRepository.findByEmail("driver@test.com")).thenReturn(Optional.of(driver));

        // When/Then
        assertThatThrownBy(() -> parcelService.deliverWithOtp("PARCEL001", "000000", "photo.jpg"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("FAILED")
                .extracting("code").isEqualTo("INVALID_OTP");

        assertThat(parcel.getOtpAttempts()).isEqualTo(3);
        assertThat(parcel.getStatus()).isEqualTo(Parcel.ParcelStatus.FAILED);
        assertThat(parcel.getProofPhotoUrl()).isNull();
        assertThat(parcel.getDeliveredAt()).isNull();
        ArgumentCaptor<Incident> captor = ArgumentCaptor.forClass(Incident.class);
        verify(incidentRepository).save(captor.capture());
        Incident incident = captor.getValue();
        assertThat(incident.getEntityType()).isEqualTo(Incident.EntityType.PARCEL);
        assertThat(incident.getEntityId()).isEqualTo(1L);
        assertThat(incident.getIncidentType()).isEqualTo(Incident.IncidentType.DELIVERY_FAIL);
        assertThat(incident.getReportedBy()).isSameAs(driver);
        assertThat(incident.getDescription()).contains("PARCEL001").doesNotContain("000000").doesNotContain(HASH);
        assertThat(incident.getCreatedAt()).isNotNull();
        verifyNoInteractions(parcelMapper);
    }

    // ==================== reopenParcel ====================

    @ParameterizedTest
    @EnumSource(value = Parcel.ParcelStatus.class, names = {"CREATED", "IN_TRANSIT", "DELIVERED"})
    void shouldReopenParcel_WhenNotFailed_ThrowInvalidStateTransition(Parcel.ParcelStatus status) {
        // Given
        parcel.setStatus(status);
        when(parcelRepository.findByCode("PARCEL001")).thenReturn(Optional.of(parcel));

        // When/Then
        assertThatThrownBy(() -> parcelService.reopenParcel("PARCEL001", null))
                .isInstanceOf(InvalidStateTransitionException.class);
        verify(parcelRepository, never()).save(any());
    }

    @ParameterizedTest
    @EnumSource(value = Trip.TripStatus.class, names = {"BOARDING", "DEPARTED", "ARRIVED"})
    void shouldReopenParcel_OnSameTrip_BackToInTransitWithAttemptsReset(Trip.TripStatus tripStatus) {
        // Given
        trip.setStatus(tripStatus);
        parcel.setStatus(Parcel.ParcelStatus.FAILED);
        parcel.setOtpAttempts(3);
        when(parcelRepository.findByCode("PARCEL001")).thenReturn(Optional.of(parcel));
        when(parcelRepository.save(parcel)).thenReturn(parcel);
        when(parcelMapper.toResponse(parcel)).thenReturn(parcelResponse);

        // When
        parcelService.reopenParcel("PARCEL001", null);

        // Then
        assertThat(parcel.getStatus()).isEqualTo(Parcel.ParcelStatus.IN_TRANSIT);
        assertThat(parcel.getOtpAttempts()).isZero();
        assertThat(parcel.getTrip()).isSameAs(trip);
        verifyNoInteractions(tripRepository);
    }

    @Test
    void shouldReopenParcel_OnCancelledTripWithoutNewTrip_ThrowInvalidStateTransition() {
        // Given
        trip.setStatus(Trip.TripStatus.CANCELLED);
        parcel.setStatus(Parcel.ParcelStatus.FAILED);
        when(parcelRepository.findByCode("PARCEL001")).thenReturn(Optional.of(parcel));

        // When/Then
        assertThatThrownBy(() -> parcelService.reopenParcel("PARCEL001", null))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("tripId");
        verify(parcelRepository, never()).save(any());
    }

    @Test
    void shouldReopenParcel_ReassignedToScheduledTrip_BackToCreated() {
        // Given
        trip.setStatus(Trip.TripStatus.CANCELLED);
        parcel.setStatus(Parcel.ParcelStatus.FAILED);
        parcel.setOtpAttempts(3);
        Trip nextTrip = Trip.builder().id(2L).route(route).status(Trip.TripStatus.SCHEDULED).build();
        when(parcelRepository.findByCode("PARCEL001")).thenReturn(Optional.of(parcel));
        when(tripRepository.findById(2L)).thenReturn(Optional.of(nextTrip));
        when(parcelRepository.save(parcel)).thenReturn(parcel);
        when(parcelMapper.toResponse(parcel)).thenReturn(parcelResponse);

        // When
        parcelService.reopenParcel("PARCEL001", 2L);

        // Then: aún no se carga en el bus
        assertThat(parcel.getTrip()).isSameAs(nextTrip);
        assertThat(parcel.getStatus()).isEqualTo(Parcel.ParcelStatus.CREATED);
        assertThat(parcel.getOtpAttempts()).isZero();
    }

    @Test
    void shouldReopenParcel_ReassignedToTripOfAnotherRoute_ThrowInvalidSegment() {
        // Given
        parcel.setStatus(Parcel.ParcelStatus.FAILED);
        Trip otherRouteTrip = Trip.builder().id(3L).route(Route.builder().id(9L).build())
                .status(Trip.TripStatus.SCHEDULED).build();
        when(parcelRepository.findByCode("PARCEL001")).thenReturn(Optional.of(parcel));
        when(tripRepository.findById(3L)).thenReturn(Optional.of(otherRouteTrip));

        // When/Then
        assertThatThrownBy(() -> parcelService.reopenParcel("PARCEL001", 3L))
                .isInstanceOf(InvalidSegmentException.class);
        assertThat(parcel.getStatus()).isEqualTo(Parcel.ParcelStatus.FAILED);
        verify(parcelRepository, never()).save(any());
    }

    @Test
    void shouldReopenParcel_ReassignedToUnknownTrip_ThrowResourceNotFound() {
        // Given
        parcel.setStatus(Parcel.ParcelStatus.FAILED);
        when(parcelRepository.findByCode("PARCEL001")).thenReturn(Optional.of(parcel));
        when(tripRepository.findById(99L)).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> parcelService.reopenParcel("PARCEL001", 99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("99");
    }

    // ==================== getTripParcels ====================

    @Test
    void shouldGetTripParcels_WithUnknownTrip_ThrowResourceNotFound() {
        // Given
        when(tripRepository.existsById(99L)).thenReturn(false);

        // When/Then
        assertThatThrownBy(() -> parcelService.getTripParcels(99L, null))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(parcelRepository);
    }

    @Test
    void shouldGetTripParcels_WithoutStatus_ReturnAllOfTheTrip() {
        // Given
        authenticate("clerk@test.com", "CLERK");
        when(tripRepository.existsById(1L)).thenReturn(true);
        when(parcelRepository.findByTripId(1L)).thenReturn(List.of(parcel));
        when(parcelMapper.toResponseList(List.of(parcel))).thenReturn(List.of(parcelResponse));

        // When
        List<ParcelResponse> result = parcelService.getTripParcels(1L, null);

        // Then
        assertThat(result).containsExactly(parcelResponse);
        verifyNoInteractions(assignmentRepository);
    }

    @Test
    void shouldGetTripParcels_AsAssignedDriverWithStatus_FilterByStatus() {
        // Given
        authenticate("driver@test.com", "DRIVER");
        when(tripRepository.existsById(1L)).thenReturn(true);
        when(assignmentRepository.findByTripId(1L)).thenReturn(Optional.of(Assignment.builder()
                .driver(User.builder().email("driver@test.com").build()).build()));
        when(parcelRepository.findByTripIdAndStatus(1L, Parcel.ParcelStatus.IN_TRANSIT)).thenReturn(List.of(parcel));
        when(parcelMapper.toResponseList(List.of(parcel))).thenReturn(List.of(parcelResponse));

        // When
        List<ParcelResponse> result = parcelService.getTripParcels(1L, Parcel.ParcelStatus.IN_TRANSIT);

        // Then
        assertThat(result).hasSize(1);
        verify(parcelRepository, never()).findByTripId(any());
    }

    @Test
    void shouldGetTripParcels_AsDriverOfAnotherTrip_ThrowForbidden() {
        // Given
        authenticate("driver@test.com", "DRIVER");
        when(tripRepository.existsById(1L)).thenReturn(true);
        when(assignmentRepository.findByTripId(1L)).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> parcelService.getTripParcels(1L, null))
                .isInstanceOf(BusinessException.class)
                .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
        verifyNoInteractions(parcelRepository);
    }

    // ==================== Consultas auxiliares ====================

    @Test
    void shouldGetParcelsInTransit_WithValidTripId_ReturnList() {
        // Given
        when(parcelRepository.findByTripIdAndStatus(1L, Parcel.ParcelStatus.IN_TRANSIT)).thenReturn(List.of(parcel));
        when(parcelMapper.toResponseList(List.of(parcel))).thenReturn(List.of(parcelResponse));

        // When
        List<ParcelResponse> result = parcelService.getParcelsInTransit(1L);

        // Then
        assertThat(result).hasSize(1);
    }

    @Test
    void shouldGetParcelsByDateRange_WithRange_DelegateToRepository() {
        // Given
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate end = LocalDate.of(2026, 1, 31);
        when(parcelRepository.findByDateRange(start, end)).thenReturn(List.of(parcel));
        when(parcelMapper.toResponseList(List.of(parcel))).thenReturn(List.of(parcelResponse));

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
