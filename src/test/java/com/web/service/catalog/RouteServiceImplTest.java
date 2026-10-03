package com.web.service.catalog;

import com.web.dto.catalog.Route.RouteCreateRequest;
import com.web.dto.catalog.Route.RouteDetailResponse;
import com.web.dto.catalog.Route.RouteResponse;
import com.web.dto.catalog.Route.RouteUpdateRequest;
import com.web.dto.catalog.Route.mapper.RouteMapper;
import com.web.dto.catalog.Stop.StopCreateRequest;
import com.web.dto.catalog.Stop.StopResponse;
import com.web.dto.catalog.Stop.StopUpdateRequest;
import com.web.dto.catalog.Stop.mapper.StopMapper;
import com.web.entity.Route;
import com.web.entity.Stop;
import com.web.exception.BusinessException;
import com.web.exception.ResourceNotFoundException;
import com.web.repository.RouteRepository;
import com.web.repository.StopRepository;
import com.web.repository.TripRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;


@ExtendWith(MockitoExtension.class)
class RouteServiceImplTest {

    @Mock
    private RouteRepository routeRepository;
    @Mock
    private StopRepository stopRepository;
    @Mock
    private TripRepository tripRepository;
    @Mock
    private RouteMapper routeMapper;
    @Mock
    private StopMapper stopMapper;

    @InjectMocks
    private RouteServiceImpl routeService;

    private Route route;
    private RouteResponse routeResponse;
    private RouteDetailResponse routeDetailResponse;

    @BeforeEach
    void setUp() {
        route = Route.builder()
                .id(1L)
                .code("R001")
                .name("Bogotá - Medellín")
                .origin("Bogotá")
                .destination("Medellín")
                .build();

        routeResponse = new RouteResponse(
                1L, "R001", "Bogotá - Medellín", "Bogotá", "Medellín", 
                java.math.BigDecimal.valueOf(500.0), 360, true
        );

        routeDetailResponse = new RouteDetailResponse(
                1L, "R001", "Bogotá - Medellín", "Bogotá", "Medellín", 
                java.math.BigDecimal.valueOf(500.0), 360, true, List.of()
        );
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "user@test.com", null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    @Test
    void shouldCreateRoute_WithValidRequest_ReturnRouteResponse() {
        // Given
        RouteCreateRequest request = new RouteCreateRequest(
                "R001", "Bogotá - Medellín", "Bogotá", "Medellín", 
                java.math.BigDecimal.valueOf(500.0), 360
        );

        when(routeRepository.existsByCode("R001")).thenReturn(false);
        when(routeMapper.toEntity(request)).thenReturn(route);
        when(routeRepository.save(any(Route.class))).thenAnswer(inv -> {
            Route r = inv.getArgument(0);
            r.setId(1L);
            return r;
        });
        when(routeMapper.toResponse(any(Route.class))).thenReturn(routeResponse);

        // When
        RouteResponse result = routeService.createRoute(request);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.id()).isEqualTo(1L);
        verify(routeRepository).existsByCode("R001");
        verify(routeRepository).save(any(Route.class));
    }

    @Test
    void shouldGetAllRoutes_HideInactiveRoutes() {
        // Given: el público solo consulta las rutas activas
        List<Route> routes = List.of(route);
        List<RouteResponse> responses = List.of(routeResponse);

        when(routeRepository.findByIsActiveTrue()).thenReturn(routes);
        when(routeMapper.toResponseList(routes)).thenReturn(responses);

        // When
        List<RouteResponse> result = routeService.getAllRoutes(false);

        // Then
        assertThat(result).isNotNull();
        assertThat(result).hasSize(1);
        verify(routeRepository).findByIsActiveTrue();
        verify(routeRepository, never()).findAll();
    }

    @Test
    void shouldGetAllRoutes_WithIncludeInactiveFromNonAdmin_IgnoreIt() {
        // Given
        authenticateAs("DISPATCHER");
        when(routeRepository.findByIsActiveTrue()).thenReturn(List.of(route));
        when(routeMapper.toResponseList(List.of(route))).thenReturn(List.of(routeResponse));

        // When
        routeService.getAllRoutes(true);

        // Then
        verify(routeRepository).findByIsActiveTrue();
        verify(routeRepository, never()).findAll();
    }

    @Test
    void shouldGetAllRoutes_WithIncludeInactiveFromAdmin_ReturnAllRoutes() {
        // Given
        authenticateAs("ADMIN");
        Route inactive = Route.builder().id(2L).code("R002").isActive(false).build();
        when(routeRepository.findAll()).thenReturn(List.of(route, inactive));
        when(routeMapper.toResponseList(List.of(route, inactive))).thenReturn(List.of(routeResponse));

        // When
        routeService.getAllRoutes(true);

        // Then
        verify(routeRepository).findAll();
        verify(routeRepository, never()).findByIsActiveTrue();
    }

    @Test
    void shouldGetRouteById_WithInactiveRoute_ThrowResourceNotFoundForPublic() {
        // Given
        route.setIsActive(false);
        when(routeRepository.findByIdWithStops(1L)).thenReturn(Optional.of(route));

        // When/Then
        assertThatThrownBy(() -> routeService.getRouteById(1L, true))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(routeMapper);
    }

    @Test
    void shouldGetRouteById_WithInactiveRouteAndAdminIncludeInactive_ReturnRoute() {
        // Given
        authenticateAs("ADMIN");
        route.setIsActive(false);
        when(routeRepository.findByIdWithStops(1L)).thenReturn(Optional.of(route));
        when(routeMapper.toDetailResponse(route)).thenReturn(routeDetailResponse);

        // When
        RouteDetailResponse result = routeService.getRouteById(1L, true);

        // Then
        assertThat(result).isSameAs(routeDetailResponse);
    }

    @Test
    void shouldGetRouteById_WithValidId_ReturnRouteDetail() {
        // Given
        when(routeRepository.findByIdWithStops(1L)).thenReturn(Optional.of(route));
        when(routeMapper.toDetailResponse(route)).thenReturn(routeDetailResponse);

        // When
        RouteDetailResponse result = routeService.getRouteById(1L, false);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.id()).isEqualTo(1L);
        verify(routeRepository).findByIdWithStops(1L);
    }

    @Test
    void shouldUpdateRoute_WithValidRequest_UpdateRoute() {
        // Given
        RouteUpdateRequest request = new RouteUpdateRequest(
                "Bogotá - Cali", null, null, java.math.BigDecimal.valueOf(600.0), 420, null
        );

        when(routeRepository.findById(1L)).thenReturn(Optional.of(route));
        when(routeRepository.save(any(Route.class))).thenReturn(route);
        when(routeMapper.toResponse(any(Route.class))).thenReturn(routeResponse);

        // When
        RouteResponse result = routeService.updateRoute(1L, request);

        // Then
        assertThat(result).isNotNull();
        verify(routeMapper).updateEntityFromRequest(request, route);
        verify(routeRepository).save(route);
    }

    @Test
    void shouldAddStop_WithValidRequest_AddStopToRoute() {
        // Given
        StopCreateRequest request = new StopCreateRequest(
                1L, "Pereira", 1, null, null
        );

        Stop stop = Stop.builder()
                .id(1L)
                .route(route)
                .name("Pereira")
                .order(1)
                .build();

        when(routeRepository.findByIdWithStops(1L)).thenReturn(Optional.of(route));
        when(stopRepository.existsByRouteIdAndOrder(1L, 1)).thenReturn(false);
        when(stopMapper.toEntity(request)).thenReturn(stop);
        when(stopRepository.save(any(Stop.class))).thenReturn(stop);
        when(routeRepository.findByIdWithStops(1L)).thenReturn(Optional.of(route));
        when(routeMapper.toDetailResponse(route)).thenReturn(routeDetailResponse);

        // When
        RouteDetailResponse result = routeService.addStop(1L, request);

        // Then
        assertThat(result).isNotNull();
        verify(stopRepository).save(any(Stop.class));
    }

    @Test
    void shouldRemoveStop_WithValidStopId_RemoveStop() {
        // Given
        Stop stop = Stop.builder()
                .id(1L)
                .route(route)
                .order(2)
                .build();

        when(routeRepository.findById(1L)).thenReturn(Optional.of(route));
        when(stopRepository.findById(1L)).thenReturn(Optional.of(stop));

        // When
        routeService.removeStop(1L, 1L);

        // Then: se borra y las paradas siguientes se corren para mantener el orden contiguo
        verify(stopRepository).delete(stop);
        verify(stopRepository).shiftOrdersAfter(1L, 2);
    }

    @Test
    void shouldCreateRoute_WithDuplicateCode_ThrowConflict() {
        // Given
        RouteCreateRequest request = new RouteCreateRequest(
                "R001", "Bogotá - Medellín", "Bogotá", "Medellín",
                java.math.BigDecimal.valueOf(500.0), 360
        );
        when(routeRepository.existsByCode("R001")).thenReturn(true);

        // When/Then
        assertThatThrownBy(() -> routeService.createRoute(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("R001")
                .extracting(ex -> ((BusinessException) ex).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);
        verify(routeRepository, never()).save(any());
        verifyNoInteractions(routeMapper);
    }

    @Test
    void shouldGetRouteById_WithNonExistentId_ThrowResourceNotFound() {
        // Given
        when(routeRepository.findByIdWithStops(99L)).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> routeService.getRouteById(99L, false))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("99");
        verifyNoInteractions(routeMapper);
    }

    @Test
    void shouldUpdateRoute_WithNonExistentId_ThrowResourceNotFound() {
        // Given
        RouteUpdateRequest request = new RouteUpdateRequest("Nueva", null, null, null, null, null);
        when(routeRepository.findById(99L)).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> routeService.updateRoute(99L, request))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(routeRepository, never()).save(any());
        verifyNoInteractions(routeMapper);
    }

    @Test
    void shouldDeleteRoute_WithoutFutureTrips_DeactivateRouteLogically() {
        // Given: la ruta no tiene viajes pendientes
        when(routeRepository.findById(1L)).thenReturn(Optional.of(route));
        when(tripRepository.countPendingTripsByRoute(eq(1L), any(LocalDateTime.class))).thenReturn(0L);
        when(routeRepository.save(route)).thenReturn(route);

        // When
        routeService.deleteRoute(1L);

        // Then: borrado lógico, nunca un DELETE físico
        ArgumentCaptor<Route> captor = ArgumentCaptor.forClass(Route.class);
        verify(routeRepository).save(captor.capture());
        assertThat(captor.getValue().getIsActive()).isFalse();
        verify(routeRepository, never()).delete(any(Route.class));
        verify(routeRepository, never()).deleteById(anyLong());
    }

    @Test
    void shouldDeleteRoute_CountPendingTripsFromNow() {
        // Given
        when(routeRepository.findById(1L)).thenReturn(Optional.of(route));
        when(tripRepository.countPendingTripsByRoute(eq(1L), any(LocalDateTime.class))).thenReturn(0L);
        when(routeRepository.save(route)).thenReturn(route);
        LocalDateTime before = LocalDateTime.now();

        // When
        routeService.deleteRoute(1L);

        // Then: el corte es el momento actual (los viajes que ya salieron no bloquean el borrado)
        ArgumentCaptor<LocalDateTime> nowCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(tripRepository).countPendingTripsByRoute(eq(1L), nowCaptor.capture());
        assertThat(nowCaptor.getValue()).isAfterOrEqualTo(before).isBeforeOrEqualTo(LocalDateTime.now());
    }

    @Test
    void shouldDeleteRoute_WithScheduledTrips_ThrowAndKeepRouteActive() {
        // Given: la ruta tiene viajes programados
        when(routeRepository.findById(1L)).thenReturn(Optional.of(route));
        when(tripRepository.countPendingTripsByRoute(eq(1L), any(LocalDateTime.class))).thenReturn(2L);

        // When/Then: conflicto (409)
        assertThatThrownBy(() -> routeService.deleteRoute(1L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    assertThat(((BusinessException) ex).getCode()).isEqualTo("ROUTE_HAS_TRIPS");
                    assertThat(((BusinessException) ex).getStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        assertThat(route.getIsActive()).isTrue();
        verify(routeRepository, never()).save(any());
        verify(routeRepository, never()).delete(any(Route.class));
    }

    @Test
    void shouldDeleteRoute_WithNonExistentId_ThrowResourceNotFound() {
        // Given
        when(routeRepository.findById(99L)).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> routeService.deleteRoute(99L))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(tripRepository);
        verify(routeRepository, never()).save(any());
    }

    @Test
    void shouldAddStop_ReturnRouteWithNewStopSortedByOrder() {
        // Given: la ruta ya tiene paradas con orden 1 y 2 (desordenadas en la lista), se agrega la de orden 3
        Stop first = Stop.builder().id(10L).route(route).name("Bogotá").order(1).build();
        Stop second = Stop.builder().id(30L).route(route).name("Honda").order(2).build();
        route.setStops(new ArrayList<>(List.of(second, first)));

        StopCreateRequest request = new StopCreateRequest(1L, "Medellín", 3, null, null);
        Stop newStop = Stop.builder().name("Medellín").order(3).build();

        when(routeRepository.findByIdWithStops(1L)).thenReturn(Optional.of(route));
        when(stopRepository.existsByRouteIdAndOrder(1L, 3)).thenReturn(false);
        when(stopMapper.toEntity(request)).thenReturn(newStop);
        when(stopRepository.save(newStop)).thenAnswer(inv -> {
            Stop s = inv.getArgument(0);
            s.setId(20L);
            return s;
        });
        when(routeMapper.toDetailResponse(route)).thenReturn(routeDetailResponse);

        // When
        RouteDetailResponse result = routeService.addStop(1L, request);

        // Then: la ruta que se mapea contiene la parada nueva y está ordenada
        assertThat(result).isSameAs(routeDetailResponse);
        ArgumentCaptor<Route> captor = ArgumentCaptor.forClass(Route.class);
        verify(routeMapper).toDetailResponse(captor.capture());
        assertThat(captor.getValue().getStops())
                .extracting(Stop::getOrder)
                .containsExactly(1, 2, 3);
        assertThat(captor.getValue().getStops())
                .extracting(Stop::getId)
                .containsExactly(10L, 30L, 20L);
        assertThat(newStop.getRoute()).isSameAs(route);
        // La ruta no se vuelve a consultar tras guardar la parada
        verify(routeRepository, times(1)).findByIdWithStops(1L);
    }

    @Test
    void shouldAddStop_WithRouteWithoutStops_InitializeStopList() {
        // Given: la lista de paradas de la ruta es null
        route.setStops(null);
        StopCreateRequest request = new StopCreateRequest(1L, "Pereira", 1, null, null);
        Stop newStop = Stop.builder().id(5L).name("Pereira").order(1).build();

        when(routeRepository.findByIdWithStops(1L)).thenReturn(Optional.of(route));
        when(stopRepository.existsByRouteIdAndOrder(1L, 1)).thenReturn(false);
        when(stopMapper.toEntity(request)).thenReturn(newStop);
        when(stopRepository.save(newStop)).thenReturn(newStop);
        when(routeMapper.toDetailResponse(route)).thenReturn(routeDetailResponse);

        // When
        routeService.addStop(1L, request);

        // Then
        assertThat(route.getStops()).containsExactly(newStop);
    }

    @Test
    void shouldAddStop_WithDuplicateOrder_ThrowConflict() {
        // Given
        StopCreateRequest request = new StopCreateRequest(1L, "Pereira", 2, null, null);
        when(routeRepository.findByIdWithStops(1L)).thenReturn(Optional.of(route));
        when(stopRepository.existsByRouteIdAndOrder(1L, 2)).thenReturn(true);

        // When/Then
        assertThatThrownBy(() -> routeService.addStop(1L, request))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo("STOP_ORDER_EXISTS");
        verify(stopRepository, never()).save(any());
        verifyNoInteractions(stopMapper, routeMapper);
    }

    @Test
    void shouldAddStop_WithNonContiguousOrder_ThrowBadRequest() {
        // Given: la ruta tiene 2 paradas; la siguiente debe ser la 3, no la 5
        route.setStops(new ArrayList<>(List.of(
                Stop.builder().id(10L).route(route).order(1).build(),
                Stop.builder().id(11L).route(route).order(2).build())));
        StopCreateRequest request = new StopCreateRequest(null, "Pereira", 5, null, null);
        when(routeRepository.findByIdWithStops(1L)).thenReturn(Optional.of(route));
        when(stopRepository.existsByRouteIdAndOrder(1L, 5)).thenReturn(false);

        // When/Then
        assertThatThrownBy(() -> routeService.addStop(1L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("3")
                .satisfies(ex -> {
                    assertThat(((BusinessException) ex).getCode()).isEqualTo("STOP_ORDER_NOT_CONTIGUOUS");
                    assertThat(((BusinessException) ex).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
        verify(stopRepository, never()).save(any());
    }

    @Test
    void shouldAddStop_WithRouteIdDifferentFromPath_ThrowBadRequest() {
        // Given
        StopCreateRequest request = new StopCreateRequest(2L, "Pereira", 1, null, null);
        when(routeRepository.findByIdWithStops(1L)).thenReturn(Optional.of(route));

        // When/Then
        assertThatThrownBy(() -> routeService.addStop(1L, request))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    assertThat(((BusinessException) ex).getCode()).isEqualTo("STOP_ROUTE_MISMATCH");
                    assertThat(((BusinessException) ex).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
        verifyNoInteractions(stopRepository, stopMapper);
    }

    @Test
    void shouldAddStop_WithoutRouteIdInBody_UsePathRoute() {
        // Given
        route.setStops(null);
        StopCreateRequest request = new StopCreateRequest(null, "Pereira", 1, null, null);
        Stop newStop = Stop.builder().id(5L).name("Pereira").order(1).build();
        when(routeRepository.findByIdWithStops(1L)).thenReturn(Optional.of(route));
        when(stopRepository.existsByRouteIdAndOrder(1L, 1)).thenReturn(false);
        when(stopMapper.toEntity(request)).thenReturn(newStop);
        when(stopRepository.save(newStop)).thenReturn(newStop);
        when(routeMapper.toDetailResponse(route)).thenReturn(routeDetailResponse);

        // When
        routeService.addStop(1L, request);

        // Then
        assertThat(newStop.getRoute()).isSameAs(route);
    }

    // ---------- updateStop ----------

    @Test
    void shouldUpdateStop_WithValidRequest_UpdateNameAndCoordinates() {
        // Given
        Stop stop = Stop.builder().id(5L).route(route).name("Honda").order(2).build();
        StopUpdateRequest request = new StopUpdateRequest("Honda Centro", new BigDecimal("5.2"), new BigDecimal("-74.7"));
        StopResponse response = new StopResponse(5L, "Honda Centro", 2, new BigDecimal("5.2"), new BigDecimal("-74.7"));
        when(routeRepository.existsById(1L)).thenReturn(true);
        when(stopRepository.findById(5L)).thenReturn(Optional.of(stop));
        when(stopRepository.save(stop)).thenReturn(stop);
        when(stopMapper.toResponse(stop)).thenReturn(response);

        // When
        StopResponse result = routeService.updateStop(1L, 5L, request);

        // Then
        assertThat(result).isSameAs(response);
        verify(stopMapper).updateEntityFromRequest(request, stop);
        verify(stopRepository).save(stop);
    }

    @Test
    void shouldUpdateStop_WithStopFromAnotherRoute_ThrowMismatch() {
        // Given
        Stop stop = Stop.builder().id(5L).route(Route.builder().id(2L).build()).order(1).build();
        when(routeRepository.existsById(1L)).thenReturn(true);
        when(stopRepository.findById(5L)).thenReturn(Optional.of(stop));

        // When/Then
        assertThatThrownBy(() -> routeService.updateStop(1L, 5L, new StopUpdateRequest("X", null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo("STOP_ROUTE_MISMATCH");
        verify(stopRepository, never()).save(any());
    }

    @Test
    void shouldUpdateStop_WithNonExistentRoute_ThrowResourceNotFound() {
        // Given
        when(routeRepository.existsById(99L)).thenReturn(false);

        // When/Then
        assertThatThrownBy(() -> routeService.updateStop(99L, 5L, new StopUpdateRequest("X", null, null)))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(stopRepository);
    }

    @Test
    void shouldAddStop_WithNonExistentRoute_ThrowResourceNotFound() {
        // Given
        StopCreateRequest request = new StopCreateRequest(99L, "Pereira", 2, null, null);
        when(routeRepository.findByIdWithStops(99L)).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> routeService.addStop(99L, request))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(stopRepository, stopMapper);
    }

    @Test
    void shouldRemoveStop_WithStopFromAnotherRoute_ThrowMismatch() {
        // Given: la parada pertenece a la ruta 2, no a la 1
        Route otherRoute = Route.builder().id(2L).code("R002").build();
        Stop stop = Stop.builder().id(5L).route(otherRoute).build();

        when(routeRepository.findById(1L)).thenReturn(Optional.of(route));
        when(stopRepository.findById(5L)).thenReturn(Optional.of(stop));

        // When/Then
        assertThatThrownBy(() -> routeService.removeStop(1L, 5L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("no pertenece")
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo("STOP_ROUTE_MISMATCH");
        verify(stopRepository, never()).delete(any(Stop.class));
    }

    @Test
    void shouldRemoveStop_WithNonExistentRoute_ThrowResourceNotFound() {
        // Given
        when(routeRepository.findById(99L)).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> routeService.removeStop(99L, 1L))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(stopRepository);
    }

    @Test
    void shouldRemoveStop_WithNonExistentStop_ThrowResourceNotFound() {
        // Given
        when(routeRepository.findById(1L)).thenReturn(Optional.of(route));
        when(stopRepository.findById(99L)).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> routeService.removeStop(1L, 99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Parada");
        verify(stopRepository, never()).delete(any(Stop.class));
    }

    @Test
    void shouldFindRoutesConnecting_ReturnMappedRoutes() {
        // Given
        List<Route> routes = List.of(route);
        when(routeRepository.findRoutesConnecting("Bogotá", "Medellín")).thenReturn(routes);
        when(routeMapper.toResponseList(routes)).thenReturn(List.of(routeResponse));

        // When
        List<RouteResponse> result = routeService.findRoutesConnecting("Bogotá", "Medellín");

        // Then
        assertThat(result).containsExactly(routeResponse);
    }

    @Test
    void shouldRemoveStop_WhenReferencedByTicketsParcelsOrFares_ThrowConflict() {
        // Given
        Route owner = Route.builder().id(1L).build();
        Stop referenced = Stop.builder().id(5L).route(owner).order(2).build();
        when(routeRepository.findById(1L)).thenReturn(Optional.of(owner));
        when(stopRepository.findById(5L)).thenReturn(Optional.of(referenced));
        when(stopRepository.isReferenced(5L)).thenReturn(true);

        // When/Then
        assertThatThrownBy(() -> routeService.removeStop(1L, 5L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    assertThat(((BusinessException) ex).getCode()).isEqualTo("STOP_IN_USE");
                    assertThat(((BusinessException) ex).getStatus()).isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
                });
        verify(stopRepository, never()).delete(any());
    }
}

