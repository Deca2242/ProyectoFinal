package com.web.service.dispatch;

import com.web.dto.dispatch.Assignment.AssignmentCreateRequest;
import com.web.dto.dispatch.Assignment.AssignmentUpdateRequest;
import com.web.dto.dispatch.Assignment.mapper.AssignmentMapper;
import com.web.entity.Assignment;
import com.web.entity.Trip;
import com.web.entity.User;
import com.web.exception.BusinessException;
import com.web.exception.InvalidStateTransitionException;
import com.web.repository.AssignmentRepository;
import com.web.repository.TripRepository;
import com.web.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

// Disponibilidad del conductor, despachador autenticado y bloqueo del checklist tras la salida
@ExtendWith(MockitoExtension.class)
class AssignmentAvailabilityRulesTest {

    @Mock
    private AssignmentRepository assignmentRepository;
    @Mock
    private TripRepository tripRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private AssignmentMapper assignmentMapper;

    @InjectMocks
    private AssignmentServiceImpl assignmentService;

    private Trip trip;
    private User driver;
    private User dispatcher;

    @BeforeEach
    void setUp() {
        LocalDate date = LocalDate.now().plusDays(2);
        trip = Trip.builder()
                .id(1L)
                .tripDate(date)
                .departureTime(date.atTime(8, 0))
                .arrivalEta(date.atTime(10, 0))
                .status(Trip.TripStatus.SCHEDULED)
                .build();
        driver = User.builder().id(10L).email("driver@test.com").role(User.Role.DRIVER).status(User.Status.ACTIVE).build();
        dispatcher = User.builder().id(20L).email("disp@test.com").role(User.Role.DISPATCHER).build();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String email, String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                email, null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    @Test
    void shouldAssignTrip_WithBusyDriver_ThrowConflict() {
        // Given
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(userRepository.findById(10L)).thenReturn(Optional.of(driver));
        when(assignmentRepository.isDriverAvailableExcludingTrip(10L, 1L, trip.getDepartureTime(), trip.getArrivalEta()))
                .thenReturn(false);

        // When/Then
        assertThatThrownBy(() -> assignmentService.assignTrip(new AssignmentCreateRequest(1L, 10L, 20L)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    assertThat(((BusinessException) ex).getCode()).isEqualTo("DRIVER_NOT_AVAILABLE");
                    assertThat(((BusinessException) ex).getStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        verify(assignmentRepository, never()).save(any());
    }

    @Test
    void shouldAssignTrip_WithInactiveDriver_ThrowBadRequest() {
        // Given
        driver.setStatus(User.Status.INACTIVE);
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(userRepository.findById(10L)).thenReturn(Optional.of(driver));

        // When/Then
        assertThatThrownBy(() -> assignmentService.assignTrip(new AssignmentCreateRequest(1L, 10L, 20L)))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("DRIVER_INACTIVE");
        verify(assignmentRepository, never()).isDriverAvailableExcludingTrip(any(), any(), any(), any());
    }

    @Test
    void shouldAssignTrip_WithoutArrivalEta_CheckAvailabilityWithDepartureTime() {
        // Given
        trip.setArrivalEta(null);
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(userRepository.findById(10L)).thenReturn(Optional.of(driver));
        when(assignmentRepository.isDriverAvailableExcludingTrip(10L, 1L, trip.getDepartureTime(), trip.getDepartureTime()))
                .thenReturn(false);

        // When/Then
        assertThatThrownBy(() -> assignmentService.assignTrip(new AssignmentCreateRequest(1L, 10L, 20L)))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("DRIVER_NOT_AVAILABLE");
    }

    @Test
    void shouldAssignTrip_WithAuthenticatedDispatcherAndNoDispatcherId_UseTokenUser() {
        // Given: el body no trae dispatcherId; el despachador sale del token
        authenticateAs("disp@test.com", "DISPATCHER");
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(userRepository.findById(10L)).thenReturn(Optional.of(driver));
        when(assignmentRepository.isDriverAvailableExcludingTrip(any(), any(), any(), any())).thenReturn(true);
        when(userRepository.findByEmail("disp@test.com")).thenReturn(Optional.of(dispatcher));
        AssignmentCreateRequest request = new AssignmentCreateRequest(1L, 10L, null);
        when(assignmentMapper.toEntity(request)).thenReturn(new Assignment());
        when(assignmentRepository.save(any(Assignment.class))).thenAnswer(inv -> inv.getArgument(0));

        // When
        assignmentService.assignTrip(request);

        // Then
        verify(assignmentRepository).save(argThat(a -> a.getDispatcher() == dispatcher));
    }

    @Test
    void shouldAssignTrip_WithDispatcherIdDifferentFromToken_ThrowBadRequest() {
        // Given: el body trae otro dispatcherId (99) distinto del despachador autenticado (20)
        authenticateAs("disp@test.com", "DISPATCHER");
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(userRepository.findById(10L)).thenReturn(Optional.of(driver));
        when(assignmentRepository.isDriverAvailableExcludingTrip(any(), any(), any(), any())).thenReturn(true);
        when(userRepository.findByEmail("disp@test.com")).thenReturn(Optional.of(dispatcher));

        // When/Then
        assertThatThrownBy(() -> assignmentService.assignTrip(new AssignmentCreateRequest(1L, 10L, 99L)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    assertThat(((BusinessException) ex).getCode()).isEqualTo("DISPATCHER_MISMATCH");
                    assertThat(((BusinessException) ex).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
        verify(userRepository, never()).findById(99L);
        verify(assignmentRepository, never()).save(any());
    }

    @Test
    void shouldAssignTrip_WithDispatcherIdEqualToToken_Assign() {
        // Given
        authenticateAs("disp@test.com", "DISPATCHER");
        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(userRepository.findById(10L)).thenReturn(Optional.of(driver));
        when(assignmentRepository.isDriverAvailableExcludingTrip(any(), any(), any(), any())).thenReturn(true);
        when(userRepository.findByEmail("disp@test.com")).thenReturn(Optional.of(dispatcher));
        AssignmentCreateRequest request = new AssignmentCreateRequest(1L, 10L, 20L);
        when(assignmentMapper.toEntity(request)).thenReturn(new Assignment());
        when(assignmentRepository.save(any(Assignment.class))).thenAnswer(inv -> inv.getArgument(0));

        // When
        assignmentService.assignTrip(request);

        // Then
        verify(assignmentRepository).save(argThat(a -> a.getDispatcher() == dispatcher));
    }

    @ParameterizedTest
    @EnumSource(value = Trip.TripStatus.class, names = {"DEPARTED", "ARRIVED", "CANCELLED"})
    void shouldUpdateChecklist_AfterDeparture_ThrowInvalidStateTransition(Trip.TripStatus status) {
        // Given
        trip.setStatus(status);
        Assignment assignment = Assignment.builder().id(5L).trip(trip).driver(driver).build();
        when(assignmentRepository.findById(5L)).thenReturn(Optional.of(assignment));

        // When/Then
        assertThatThrownBy(() -> assignmentService.updateChecklist(5L, new AssignmentUpdateRequest(null, true, true, true)))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining(status.name());
        verify(assignmentRepository, never()).save(any());
    }

    @Test
    void shouldUpdateChecklist_ChangingToBusyDriver_ThrowConflict() {
        // Given
        User other = User.builder().id(11L).role(User.Role.DRIVER).status(User.Status.ACTIVE).build();
        Assignment assignment = Assignment.builder().id(5L).trip(trip).driver(driver).build();
        when(assignmentRepository.findById(5L)).thenReturn(Optional.of(assignment));
        when(userRepository.findById(11L)).thenReturn(Optional.of(other));
        when(assignmentRepository.isDriverAvailableExcludingTrip(any(), any(), any(), any())).thenReturn(false);

        // When/Then
        assertThatThrownBy(() -> assignmentService.updateChecklist(5L, new AssignmentUpdateRequest(11L, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("DRIVER_NOT_AVAILABLE");
    }

    @Test
    void shouldUpdateChecklist_KeepingSameDriver_NotCheckAvailability() {
        // Given: el mismo conductor ya ocupa este horario con este viaje
        Assignment assignment = Assignment.builder().id(5L).trip(trip).driver(driver).build();
        when(assignmentRepository.findById(5L)).thenReturn(Optional.of(assignment));
        when(userRepository.findById(10L)).thenReturn(Optional.of(driver));
        when(assignmentRepository.save(any(Assignment.class))).thenAnswer(inv -> inv.getArgument(0));

        // When
        assignmentService.updateChecklist(5L, new AssignmentUpdateRequest(10L, true, true, true));

        // Then
        verify(assignmentRepository, never()).isDriverAvailableExcludingTrip(any(), any(), any(), any());
    }
}
