package com.web.service.dispatch;

import com.web.dto.dispatch.Assignment.AssignmentCreateRequest;
import com.web.dto.dispatch.Assignment.AssignmentResponse;
import com.web.dto.dispatch.Assignment.AssignmentUpdateRequest;
import com.web.dto.dispatch.Assignment.mapper.AssignmentMapper;
import com.web.entity.Assignment;
import com.web.entity.Trip;
import com.web.entity.User;
import com.web.exception.BusinessException;
import com.web.exception.ResourceNotFoundException;
import com.web.repository.AssignmentRepository;
import com.web.repository.TripRepository;
import com.web.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
class AssignmentServiceImplTest {

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
    private Assignment assignment;
    private AssignmentResponse assignmentResponse;

    @BeforeEach
    void setUp() {
        trip = Trip.builder()
                .id(1L)
                .status(Trip.TripStatus.SCHEDULED)
                .build();

        driver = User.builder()
                .id(1L)
                .name("Driver Name")
                .email("driver@example.com")
                .role(User.Role.DRIVER)
                .build();

        dispatcher = User.builder()
                .id(2L)
                .name("Dispatcher Name")
                .email("dispatcher@example.com")
                .role(User.Role.DISPATCHER)
                .build();

        assignment = Assignment.builder()
                .id(1L)
                .trip(trip)
                .driver(driver)
                .dispatcher(dispatcher)
                .checklistOk(false)
                .soatValid(false)
                .revisionValid(false)
                .assignedAt(LocalDateTime.now())
                .build();

        assignmentResponse = new AssignmentResponse(
                1L, 1L, 1L, "Driver Name", "123456789",
                2L, "Dispatcher Name",
                false, false, false,
                LocalDateTime.now()
        );
    }

    @Test
    void shouldAssignTrip_WithValidRequest_ReturnAssignmentResponse() {
        // Given
        AssignmentCreateRequest request = new AssignmentCreateRequest(1L, 1L, 2L);

        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(assignmentRepository.findByTripId(1L)).thenReturn(Optional.empty());
        when(userRepository.findById(1L)).thenReturn(Optional.of(driver));
        when(userRepository.findById(2L)).thenReturn(Optional.of(dispatcher));
        when(assignmentMapper.toEntity(request)).thenReturn(assignment);
        when(assignmentRepository.save(any(Assignment.class))).thenAnswer(inv -> {
            Assignment a = inv.getArgument(0);
            a.setId(1L);
            return a;
        });
        when(assignmentMapper.toResponse(any(Assignment.class))).thenReturn(assignmentResponse);

        // When
        AssignmentResponse result = assignmentService.assignTrip(request);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.id()).isEqualTo(1L);
        verify(tripRepository).findById(1L);
        verify(assignmentRepository).save(any(Assignment.class));
    }

    @Test
    void shouldAssignTrip_WithInvalidTripStatus_ThrowException() {
        // Given
        trip.setStatus(Trip.TripStatus.DEPARTED);
        AssignmentCreateRequest request = new AssignmentCreateRequest(1L, 1L, 2L);

        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));

        // When/Then
        assertThatThrownBy(() -> assignmentService.assignTrip(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("SCHEDULED");
    }

    @Test
    void shouldAssignTrip_WithInvalidDriverRole_ThrowException() {
        // Given
        driver.setRole(User.Role.PASSENGER);
        AssignmentCreateRequest request = new AssignmentCreateRequest(1L, 1L, 2L);

        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(assignmentRepository.findByTripId(1L)).thenReturn(Optional.empty());
        when(userRepository.findById(1L)).thenReturn(Optional.of(driver));

        // When/Then
        assertThatThrownBy(() -> assignmentService.assignTrip(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("conductor");
    }

    @Test
    void shouldUpdateChecklist_WithValidRequest_UpdateFields() {
        // Given
        AssignmentUpdateRequest request = new AssignmentUpdateRequest(
                null, true, true, true
        );

        when(assignmentRepository.findById(1L)).thenReturn(Optional.of(assignment));
        when(assignmentRepository.save(any(Assignment.class))).thenReturn(assignment);
        when(assignmentMapper.toResponse(any(Assignment.class))).thenReturn(assignmentResponse);

        // When
        AssignmentResponse result = assignmentService.updateChecklist(1L, request);

        // Then
        assertThat(result).isNotNull();
        verify(assignmentMapper).updateEntityFromRequest(request, assignment);
        verify(assignmentRepository).save(assignment);
    }

    @Test
    void shouldGetAssignmentByTrip_WithValidTripId_ReturnAssignment() {
        // Given
        when(assignmentRepository.findByTripId(1L)).thenReturn(Optional.of(assignment));
        when(assignmentMapper.toResponse(assignment)).thenReturn(assignmentResponse);

        // When
        AssignmentResponse result = assignmentService.getAssignmentByTrip(1L);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.id()).isEqualTo(1L);
        verify(assignmentRepository).findByTripId(1L);
    }

    @Test
    void shouldGetDriverAssignments_WithValidDriverId_ReturnList() {
        // Given: sin fecha se devuelven todas las asignaciones del conductor
        List<Assignment> assignments = List.of(assignment);
        List<AssignmentResponse> responses = List.of(assignmentResponse);

        when(assignmentRepository.findByDriverId(1L)).thenReturn(assignments);
        when(assignmentMapper.toResponseList(assignments)).thenReturn(responses);

        // When
        List<AssignmentResponse> result = assignmentService.getDriverAssignments(1L, null);

        // Then
        assertThat(result).isNotNull();
        assertThat(result).hasSize(1);
        verify(assignmentRepository).findByDriverId(1L);
    }

    @Test
    void shouldGetDriverAssignments_WithDate_FilterByDate() {
        // Given: con fecha se filtra por los viajes de ese día
        LocalDate date = LocalDate.now();
        List<Assignment> assignments = List.of(assignment);
        List<AssignmentResponse> responses = List.of(assignmentResponse);

        when(assignmentRepository.findDriverAssignmentsForDate(1L, date)).thenReturn(assignments);
        when(assignmentMapper.toResponseList(assignments)).thenReturn(responses);

        // When
        List<AssignmentResponse> result = assignmentService.getDriverAssignments(1L, date);

        // Then
        assertThat(result).hasSize(1);
        verify(assignmentRepository).findDriverAssignmentsForDate(1L, date);
        verify(assignmentRepository, never()).findByDriverId(anyLong());
    }
}

