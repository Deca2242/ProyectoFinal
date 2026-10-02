package com.web.service.incident;

import com.web.dto.incident.IncidentCreateRequest;
import com.web.dto.incident.IncidentResponse;
import com.web.dto.incident.mapper.IncidentMapper;
import com.web.entity.Incident;
import com.web.entity.User;
import com.web.exception.BusinessException;
import com.web.exception.ResourceNotFoundException;
import com.web.repository.IncidentRepository;
import com.web.repository.ParcelRepository;
import com.web.repository.TicketRepository;
import com.web.repository.TripRepository;
import com.web.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
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

// Reporte de incidentes por el personal (entidad existente, quien reporta = autenticado) y búsqueda con filtros
@ExtendWith(MockitoExtension.class)
class IncidentServiceImplTest {

    @Mock
    private IncidentRepository incidentRepository;
    @Mock
    private TripRepository tripRepository;
    @Mock
    private TicketRepository ticketRepository;
    @Mock
    private ParcelRepository parcelRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private IncidentMapper incidentMapper;

    @InjectMocks
    private IncidentServiceImpl incidentService;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate(String username, String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                username, null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private IncidentResponse response() {
        return new IncidentResponse(1L, Incident.IncidentType.VEHICLE, Incident.EntityType.TRIP, 5L,
                "Llanta pinchada", 7L, "Conductor", LocalDateTime.now());
    }

    // ---------- Reporte ----------

    @Test
    void shouldReportIncident_WithExistingTrip_SaveWithReporterAndDate() {
        // Given
        authenticate("driver@test.com", "DRIVER");
        User driver = User.builder().id(7L).email("driver@test.com").name("Conductor").build();
        IncidentCreateRequest request = new IncidentCreateRequest(Incident.IncidentType.VEHICLE,
                Incident.EntityType.TRIP, 5L, "  Llanta pinchada  ");
        Incident entity = Incident.builder().incidentType(Incident.IncidentType.VEHICLE)
                .entityType(Incident.EntityType.TRIP).entityId(5L).description(request.description()).build();
        when(tripRepository.existsById(5L)).thenReturn(true);
        when(userRepository.findByEmail("driver@test.com")).thenReturn(Optional.of(driver));
        when(incidentMapper.toEntity(request)).thenReturn(entity);
        when(incidentRepository.save(entity)).thenReturn(entity);
        when(incidentMapper.toResponse(entity)).thenReturn(response());

        // When
        IncidentResponse result = incidentService.reportIncident(request);

        // Then
        assertThat(result.type()).isEqualTo(Incident.IncidentType.VEHICLE);
        assertThat(entity.getReportedBy()).isSameAs(driver);
        assertThat(entity.getDescription()).isEqualTo("Llanta pinchada");
        assertThat(entity.getCreatedAt()).isNotNull();
    }

    @Test
    void shouldReportIncident_WithTicketAndParcel_CheckTheRightRepository() {
        // Given
        authenticate("clerk@test.com", "CLERK");
        User clerk = User.builder().id(8L).email("clerk@test.com").build();
        IncidentCreateRequest ticketRequest = new IncidentCreateRequest(Incident.IncidentType.SECURITY,
                Incident.EntityType.TICKET, 3L, "Pasajero agresivo");
        IncidentCreateRequest parcelRequest = new IncidentCreateRequest(Incident.IncidentType.DELIVERY_FAIL,
                Incident.EntityType.PARCEL, 4L, "Destinatario ausente");
        when(ticketRepository.existsById(3L)).thenReturn(true);
        when(parcelRepository.existsById(4L)).thenReturn(true);
        when(userRepository.findByEmail("clerk@test.com")).thenReturn(Optional.of(clerk));
        when(incidentMapper.toEntity(any())).thenAnswer(inv -> Incident.builder().build());
        when(incidentRepository.save(any(Incident.class))).thenAnswer(inv -> inv.getArgument(0));
        when(incidentMapper.toResponse(any(Incident.class))).thenReturn(response());

        // When
        incidentService.reportIncident(ticketRequest);
        incidentService.reportIncident(parcelRequest);

        // Then
        verify(ticketRepository).existsById(3L);
        verify(parcelRepository).existsById(4L);
        verifyNoInteractions(tripRepository);
        verify(incidentRepository, times(2)).save(any(Incident.class));
    }

    @ParameterizedTest
    @EnumSource(Incident.EntityType.class)
    void shouldReportIncident_WithNonExistentEntity_ThrowResourceNotFound(Incident.EntityType entityType) {
        // Given
        IncidentCreateRequest request = new IncidentCreateRequest(Incident.IncidentType.SECURITY,
                entityType, 99L, "Algo pasó");
        switch (entityType) {
            case TRIP -> when(tripRepository.existsById(99L)).thenReturn(false);
            case TICKET -> when(ticketRepository.existsById(99L)).thenReturn(false);
            case PARCEL -> when(parcelRepository.existsById(99L)).thenReturn(false);
        }

        // When/Then
        assertThatThrownBy(() -> incidentService.reportIncident(request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("99");
        verifyNoInteractions(incidentRepository, userRepository);
    }

    @Test
    void shouldReportIncident_WithoutAuthenticatedUser_ThrowUnauthorized() {
        // Given
        IncidentCreateRequest request = new IncidentCreateRequest(Incident.IncidentType.VEHICLE,
                Incident.EntityType.TRIP, 5L, "Falla de frenos");
        when(tripRepository.existsById(5L)).thenReturn(true);

        // When/Then
        assertThatThrownBy(() -> incidentService.reportIncident(request))
                .isInstanceOf(BusinessException.class)
                .extracting("status").isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(incidentRepository);
    }

    // ---------- Búsqueda ----------

    @Test
    @SuppressWarnings("unchecked")
    void shouldSearchIncidents_WithFilters_QueryOrderedByDateDesc() {
        // Given
        Incident incident = Incident.builder().id(1L).build();
        when(incidentRepository.findAll(any(Specification.class), any(Sort.class))).thenReturn(List.of(incident));
        when(incidentMapper.toResponseList(List.of(incident))).thenReturn(List.of(response()));

        // When
        List<IncidentResponse> result = incidentService.searchIncidents(Incident.IncidentType.VEHICLE,
                Incident.EntityType.TRIP, 5L, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));

        // Then
        assertThat(result).hasSize(1);
        ArgumentCaptor<Sort> sort = ArgumentCaptor.forClass(Sort.class);
        verify(incidentRepository).findAll(any(Specification.class), sort.capture());
        assertThat(sort.getValue().getOrderFor("createdAt").getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldSearchIncidents_WithoutFilters_ReturnAll() {
        // Given
        when(incidentRepository.findAll(any(Specification.class), any(Sort.class))).thenReturn(List.of());
        when(incidentMapper.toResponseList(List.of())).thenReturn(List.of());

        // When
        List<IncidentResponse> result = incidentService.searchIncidents(null, null, null, null, null);

        // Then
        assertThat(result).isEmpty();
    }

    @Test
    void shouldSearchIncidents_WithFromAfterTo_ThrowBadRequest() {
        // When/Then
        assertThatThrownBy(() -> incidentService.searchIncidents(null, null, null,
                LocalDate.of(2026, 2, 1), LocalDate.of(2026, 1, 1)))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("INVALID_DATE_RANGE");
        verifyNoInteractions(incidentRepository);
    }
}
