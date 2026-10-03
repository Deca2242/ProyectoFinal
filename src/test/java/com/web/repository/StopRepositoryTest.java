package com.web.repository;

import com.web.entity.Route;
import com.web.entity.Stop;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;


@DisplayName("StopRepository Integration Tests")
class StopRepositoryTest extends BaseRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private StopRepository stopRepository;

    private Route route;
    private Stop stopBogota;
    private Stop stopTunja;
    private Stop stopBucaramanga;
    private Stop stopFromAnotherRoute;

    @BeforeEach
    void setUp() {
        entityManager.clear();

        // Crear ruta principal
        route = Route.builder()
                .code("BOG-BGA")
                .name("Bogotá - Bucaramanga")
                .origin("Bogotá")
                .destination("Bucaramanga")
                .distanceKm(new BigDecimal("398.50"))
                .durationMin(420)
                .isActive(true)
                .build();
        entityManager.persist(route);

        // Crear paradas en orden
        stopBogota = Stop.builder()
                .route(route)
                .name("Terminal Bogotá")
                .order(1)
                .latitude(new BigDecimal("4.6097"))
                .longitude(new BigDecimal("-74.0817"))
                .build();

        stopTunja = Stop.builder()
                .route(route)
                .name("Terminal Tunja")
                .order(2)
                .latitude(new BigDecimal("5.5353"))
                .longitude(new BigDecimal("-73.3678"))
                .build();

        stopBucaramanga = Stop.builder()
                .route(route)
                .name("Terminal Bucaramanga")
                .order(3)
                .latitude(new BigDecimal("7.1193"))
                .longitude(new BigDecimal("-73.1227"))
                .build();

        entityManager.persist(stopBogota);
        entityManager.persist(stopTunja);
        entityManager.persist(stopBucaramanga);

        // Crear otra ruta con una parada
        Route anotherRoute = Route.builder()
                .code("MDE-CTG")
                .name("Medellín - Cartagena")
                .origin("Medellín")
                .destination("Cartagena")
                .distanceKm(new BigDecimal("630.00"))
                .durationMin(720)
                .isActive(true)
                .build();
        entityManager.persist(anotherRoute);

        stopFromAnotherRoute = Stop.builder()
                .route(anotherRoute)
                .name("Terminal Medellín")
                .order(1)
                .latitude(new BigDecimal("6.2442"))
                .longitude(new BigDecimal("-75.5812"))
                .build();
        entityManager.persist(stopFromAnotherRoute);

        entityManager.flush();
    }

    @Test
    @DisplayName("Debe encontrar paradas de una ruta ordenadas por secuencia")
    void shouldFindStopsByRouteOrderedByOrder() {
        // When
        List<Stop> stops = stopRepository.findByRouteIdOrderByOrderAsc(route.getId());

        // Then
        assertThat(stops).hasSize(3);
        assertThat(stops.get(0).getName()).isEqualTo("Terminal Bogotá");
        assertThat(stops.get(0).getOrder()).isEqualTo(1);
        assertThat(stops.get(1).getName()).isEqualTo("Terminal Tunja");
        assertThat(stops.get(1).getOrder()).isEqualTo(2);
        assertThat(stops.get(2).getName()).isEqualTo("Terminal Bucaramanga");
        assertThat(stops.get(2).getOrder()).isEqualTo(3);
    }

    @Test
    @DisplayName("Debe validar si una parada pertenece a una ruta")
    void shouldValidateIfStopBelongsToRoute() {
        // When
        boolean belongs = stopRepository.existsByIdAndRouteId(stopBogota.getId(), route.getId());
        boolean notBelongs = stopRepository.existsByIdAndRouteId(stopFromAnotherRoute.getId(), route.getId());

        // Then
        assertThat(belongs).isTrue();
        assertThat(notBelongs).isFalse();
    }

    @Test
    @DisplayName("Debe obtener el orden de una parada")
    void shouldGetStopOrder() {
        // When
        Integer orderBogota = stopRepository.getStopOrder(stopBogota.getId());
        Integer orderTunja = stopRepository.getStopOrder(stopTunja.getId());
        Integer orderBucaramanga = stopRepository.getStopOrder(stopBucaramanga.getId());

        // Then
        assertThat(orderBogota).isEqualTo(1);
        assertThat(orderTunja).isEqualTo(2);
        assertThat(orderBucaramanga).isEqualTo(3);
    }

    @Test
    @DisplayName("Debe validar si un tramo es válido (fromStop antes de toStop)")
    void shouldValidateValidSegment() {
        // When - tramos válidos
        Boolean validSegment1 = stopRepository.isValidSegment(stopBogota.getId(), stopTunja.getId());
        Boolean validSegment2 = stopRepository.isValidSegment(stopTunja.getId(), stopBucaramanga.getId());
        Boolean validSegment3 = stopRepository.isValidSegment(stopBogota.getId(), stopBucaramanga.getId());

        // Then
        assertThat(validSegment1).isTrue();
        assertThat(validSegment2).isTrue();
        assertThat(validSegment3).isTrue();
    }

    @Test
    @DisplayName("Debe invalidar tramo cuando el orden es incorrecto")
    void shouldInvalidateSegmentWithWrongOrder() {
        // When - tramo inválido (destino antes que origen)
        Boolean invalidSegment = stopRepository.isValidSegment(stopBucaramanga.getId(), stopBogota.getId());

        // Then
        assertThat(invalidSegment).isFalse();
    }

    @Test
    @DisplayName("Debe invalidar tramo cuando las paradas son de rutas diferentes")
    void shouldInvalidateSegmentFromDifferentRoutes() {
        // When - paradas de diferentes rutas
        Boolean invalidSegment = stopRepository.isValidSegment(stopBogota.getId(), stopFromAnotherRoute.getId());

        // Then
        assertThat(invalidSegment).isFalse();
    }

    @Test
    @DisplayName("Debe retornar lista vacía cuando la ruta no tiene paradas")
    void shouldReturnEmptyListWhenRouteHasNoStops() {
        // Given - crear ruta sin paradas
        Route emptyRoute = Route.builder()
                .code("CAL-BAQ")
                .name("Cali - Barranquilla")
                .origin("Cali")
                .destination("Barranquilla")
                .distanceKm(new BigDecimal("1050.00"))
                .durationMin(900)
                .isActive(true)
                .build();
        entityManager.persist(emptyRoute);
        entityManager.flush();

        // When
        List<Stop> stops = stopRepository.findByRouteIdOrderByOrderAsc(emptyRoute.getId());

        // Then
        assertThat(stops).isEmpty();
    }
}
