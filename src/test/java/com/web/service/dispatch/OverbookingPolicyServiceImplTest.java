package com.web.service.dispatch;

import com.web.dto.dispatch.OverbookingPolicy.OverbookingPolicyCreateRequest;
import com.web.dto.dispatch.OverbookingPolicy.OverbookingPolicyResponse;
import com.web.dto.dispatch.OverbookingPolicy.mapper.OverbookingPolicyMapper;
import com.web.entity.OverbookingPolicy;
import com.web.entity.Route;
import com.web.entity.User;
import com.web.exception.BusinessException;
import com.web.exception.ResourceNotFoundException;
import com.web.repository.OverbookingPolicyRepository;
import com.web.repository.RouteRepository;
import com.web.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

// Políticas de overbooking por ruta y franja horaria: rango de horas válido y sin solapamiento
@ExtendWith(MockitoExtension.class)
class OverbookingPolicyServiceImplTest {

    @Mock
    private OverbookingPolicyRepository policyRepository;
    @Mock
    private RouteRepository routeRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private OverbookingPolicyMapper policyMapper;

    @InjectMocks
    private OverbookingPolicyServiceImpl policyService;

    private Route route;
    private OverbookingPolicyResponse response;

    @BeforeEach
    void setUp() {
        route = Route.builder().id(1L).name("Santa Marta - Barranquilla").build();
        response = new OverbookingPolicyResponse(1L, 1L, 6, 10, new BigDecimal("0.1000"), 7L, "Despachador",
                LocalDateTime.now());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private OverbookingPolicy policy(long id, int start, int end) {
        return OverbookingPolicy.builder().id(id).route(route).startHour(start).endHour(end)
                .maxPercentage(new BigDecimal("0.0500")).build();
    }

    private OverbookingPolicyCreateRequest request(int start, int end) {
        return new OverbookingPolicyCreateRequest(start, end, new BigDecimal("0.10"));
    }

    // ---------- Consulta ----------

    @Test
    void shouldGetPoliciesByRoute_ReturnMappedPolicies() {
        // Given
        List<OverbookingPolicy> policies = List.of(policy(1L, 0, 6), policy(2L, 6, 12));
        when(routeRepository.existsById(1L)).thenReturn(true);
        when(policyRepository.findByRouteIdOrderByStartHourAsc(1L)).thenReturn(policies);
        when(policyMapper.toResponseList(policies)).thenReturn(List.of(response));

        // When
        List<OverbookingPolicyResponse> result = policyService.getPoliciesByRoute(1L);

        // Then
        assertThat(result).containsExactly(response);
    }

    @Test
    void shouldGetPoliciesByRoute_WithNonExistentRoute_ThrowResourceNotFound() {
        // Given
        when(routeRepository.existsById(99L)).thenReturn(false);

        // When/Then
        assertThatThrownBy(() -> policyService.getPoliciesByRoute(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("99");
        verifyNoInteractions(policyRepository);
    }

    // ---------- Creación ----------

    @Test
    void shouldCreatePolicy_WithFreeRange_SaveWithRouteAndAuthenticatedCreator() {
        // Given
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "disp@test.com", null, List.of(new SimpleGrantedAuthority("ROLE_DISPATCHER"))));
        User dispatcher = User.builder().id(7L).email("disp@test.com").build();
        OverbookingPolicyCreateRequest request = request(6, 10);
        OverbookingPolicy entity = OverbookingPolicy.builder().startHour(6).endHour(10)
                .maxPercentage(new BigDecimal("0.10")).build();
        when(routeRepository.findById(1L)).thenReturn(Optional.of(route));
        when(policyRepository.findByRouteIdOrderByStartHourAsc(1L))
                .thenReturn(List.of(policy(1L, 0, 6), policy(2L, 10, 14)));
        when(policyMapper.toEntity(request)).thenReturn(entity);
        when(userRepository.findByEmail("disp@test.com")).thenReturn(Optional.of(dispatcher));
        when(policyRepository.save(entity)).thenReturn(entity);
        when(policyMapper.toResponse(entity)).thenReturn(response);

        // When
        OverbookingPolicyResponse result = policyService.createPolicy(1L, request);

        // Then: las franjas contiguas (0-6 y 10-14) no se solapan con 6-10
        assertThat(result).isSameAs(response);
        assertThat(entity.getRoute()).isSameAs(route);
        assertThat(entity.getCreatedBy()).isSameAs(dispatcher);
    }

    @Test
    void shouldCreatePolicy_WithoutAuthentication_SaveWithoutCreator() {
        // Given
        OverbookingPolicyCreateRequest request = request(0, 24);
        OverbookingPolicy entity = OverbookingPolicy.builder().startHour(0).endHour(24).build();
        when(routeRepository.findById(1L)).thenReturn(Optional.of(route));
        when(policyRepository.findByRouteIdOrderByStartHourAsc(1L)).thenReturn(List.of());
        when(policyMapper.toEntity(request)).thenReturn(entity);
        when(policyRepository.save(entity)).thenReturn(entity);
        when(policyMapper.toResponse(entity)).thenReturn(response);

        // When
        policyService.createPolicy(1L, request);

        // Then
        assertThat(entity.getCreatedBy()).isNull();
        verifyNoInteractions(userRepository);
    }

    @Test
    void shouldCreatePolicy_WithNonExistentRoute_ThrowResourceNotFound() {
        // Given
        when(routeRepository.findById(99L)).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> policyService.createPolicy(99L, request(6, 10)))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Ruta");
        verify(policyRepository, never()).save(any());
    }

    @ParameterizedTest
    @CsvSource({"10, 10", "12, 6", "23, 1"})
    void shouldCreatePolicy_WithStartNotBeforeEnd_ThrowBadRequest(int start, int end) {
        // Given
        when(routeRepository.findById(1L)).thenReturn(Optional.of(route));

        // When/Then
        assertThatThrownBy(() -> policyService.createPolicy(1L, request(start, end)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(be.getCode()).isEqualTo("INVALID_HOUR_RANGE");
                });
        verifyNoInteractions(policyRepository);
    }

    @ParameterizedTest
    @CsvSource({"5, 7", "8, 12", "6, 10", "0, 24", "9, 10"})
    void shouldCreatePolicy_WithOverlappingRange_ThrowConflict(int start, int end) {
        // Given: ya existe la franja 6-10
        when(routeRepository.findById(1L)).thenReturn(Optional.of(route));
        when(policyRepository.findByRouteIdOrderByStartHourAsc(1L)).thenReturn(List.of(policy(1L, 6, 10)));

        // When/Then
        assertThatThrownBy(() -> policyService.createPolicy(1L, request(start, end)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(be.getCode()).isEqualTo("OVERBOOKING_POLICY_OVERLAP");
                });
        verify(policyRepository, never()).save(any());
    }

    // ---------- Eliminación ----------

    @Test
    void shouldDeletePolicy_WithExistingPolicy_DeleteIt() {
        // Given
        OverbookingPolicy policy = policy(1L, 6, 10);
        when(policyRepository.findById(1L)).thenReturn(Optional.of(policy));

        // When
        policyService.deletePolicy(1L);

        // Then
        verify(policyRepository).delete(policy);
    }

    @Test
    void shouldDeletePolicy_WithNonExistentPolicy_ThrowResourceNotFound() {
        // Given
        when(policyRepository.findById(99L)).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> policyService.deletePolicy(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("99");
        verify(policyRepository, never()).delete(any());
    }
}
