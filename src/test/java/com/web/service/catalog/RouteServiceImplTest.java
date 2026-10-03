package com.web.service.catalog;

import com.web.dto.catalog.Route.RouteCreateRequest;
import com.web.dto.catalog.Route.RouteDetailResponse;
import com.web.dto.catalog.Route.RouteResponse;
import com.web.dto.catalog.Route.RouteUpdateRequest;
import com.web.dto.catalog.Route.mapper.RouteMapper;
import com.web.dto.catalog.Stop.StopCreateRequest;
import com.web.dto.catalog.Stop.mapper.StopMapper;
import com.web.entity.Route;
import com.web.entity.Stop;
import com.web.exception.BusinessException;
import com.web.exception.ResourceNotFoundException;
import com.web.repository.RouteRepository;
import com.web.repository.StopRepository;
import com.web.repository.TripRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
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
    void shouldGetAllRoutes_ReturnRouteList() {
        // Given
        List<Route> routes = List.of(route);
        List<RouteResponse> responses = List.of(routeResponse);

        when(routeRepository.findAll()).thenReturn(routes);
        when(routeMapper.toResponseList(routes)).thenReturn(responses);

        // When
        List<RouteResponse> result = routeService.getAllRoutes();

        // Then
        assertThat(result).isNotNull();
        assertThat(result).hasSize(1);
        verify(routeRepository).findAll();
    }

    @Test
    void shouldGetRouteById_WithValidId_ReturnRouteDetail() {
        // Given
        when(routeRepository.findByIdWithStops(1L)).thenReturn(Optional.of(route));
        when(routeMapper.toDetailResponse(route)).thenReturn(routeDetailResponse);

        // When
        RouteDetailResponse result = routeService.getRouteById(1L);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.id()).isEqualTo(1L);
        verify(routeRepository).findByIdWithStops(1L);
    }

    @Test
    void shouldUpdateRoute_WithValidRequest_UpdateRoute() {
        // Given
        RouteUpdateRequest request = new RouteUpdateRequest(
                "Bogotá - Cali", java.math.BigDecimal.valueOf(600.0), 420, null
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
                1L, "Pereira", 2, null, null
        );

        Stop stop = Stop.builder()
                .id(1L)
                .route(route)
                .name("Pereira")
                .order(2)
                .build();

        when(routeRepository.findByIdWithStops(1L)).thenReturn(Optional.of(route));
        when(stopRepository.existsByRouteIdAndOrder(1L, 2)).thenReturn(false);
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
                .build();

        when(routeRepository.findById(1L)).thenReturn(Optional.of(route));
        when(stopRepository.findById(1L)).thenReturn(Optional.of(stop));

        // When
        routeService.removeStop(1L, 1L);

        // Then
        verify(stopRepository).delete(stop);
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
        assertThatThrownBy(() -> routeService.getRouteById(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("99");
        verifyNoInteractions(routeMapper);
    }

    @Test
    void shouldUpdateRoute_WithNonExistentId_ThrowResourceNotFound() {
        // Given
        RouteUpdateRequest request = new RouteUpdateRequest("Nueva", null, null, null);
        when(routeRepository.findById(99L)).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> routeService.updateRoute(99L, request))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(routeRepository, never()).save(any());
        verifyNoInteractions(routeMapper);
    }

    @Test
    void shouldDeleteRoute_WithoutFutureTrips_DeactivateRouteLogically() {
        // Given: la ruta no tiene viajes desde hoy en adelante
        when(routeRepository.findById(1L)).thenReturn(Optional.of(route));
        when(tripRepository.countByRouteIdAndTripDateGreaterThanEqual(1L, LocalDate.now())).thenReturn(0L);
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
    void shouldDeleteRoute_CountTripsFromTodayInclusive() {
        // Given
        when(routeRepository.findById(1L)).thenReturn(Optional.of(route));
        when(tripRepository.countByRouteIdAndTripDateGreaterThanEqual(eq(1L), any(LocalDate.class))).thenReturn(0L);
        when(routeRepository.save(route)).thenReturn(route);

        // When
        routeService.deleteRoute(1L);

        // Then: la fecha de corte es hoy (los viajes de hoy también bloquean el borrado)
        ArgumentCaptor<LocalDate> dateCaptor = ArgumentCaptor.forClass(LocalDate.class);
        verify(tripRepository).countByRouteIdAndTripDateGreaterThanEqual(eq(1L), dateCaptor.capture());
        assertThat(dateCaptor.getValue()).isEqualTo(LocalDate.now());
    }

    @Test
    void shouldDeleteRoute_WithScheduledTrips_ThrowAndKeepRouteActive() {
        // Given: la ruta tiene viajes programados
        when(routeRepository.findById(1L)).thenReturn(Optional.of(route));
        when(tripRepository.countByRouteIdAndTripDateGreaterThanEqual(1L, LocalDate.now())).thenReturn(2L);

        // When/Then
        assertThatThrownBy(() -> routeService.deleteRoute(1L))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo("ROUTE_HAS_TRIPS");
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
        // Given: la ruta ya tiene paradas con orden 1 y 3, se agrega la de orden 2
        Stop first = Stop.builder().id(10L).route(route).name("Bogotá").order(1).build();
        Stop last = Stop.builder().id(30L).route(route).name("Medellín").order(3).build();
        route.setStops(new ArrayList<>(List.of(first, last)));

        StopCreateRequest request = new StopCreateRequest(1L, "Honda", 2, null, null);
        Stop newStop = Stop.builder().name("Honda").order(2).build();

        when(routeRepository.findByIdWithStops(1L)).thenReturn(Optional.of(route));
        when(stopRepository.existsByRouteIdAndOrder(1L, 2)).thenReturn(false);
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
                .containsExactly(10L, 20L, 30L);
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

