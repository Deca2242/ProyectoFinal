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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;


@DisplayName("RouteRepository Integration Tests")
class RouteRepositoryTest extends BaseRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private RouteRepository routeRepository;

    private Route routeBogotaBucaramanga;
    private Route routeBogotaMedellin;
    private Route routeMedellinCartagena;
    private Route inactiveRoute;

    @BeforeEach
    void setUp() {
        entityManager.clear();

        // Ruta activa: Bogotá → Tunja → Bucaramanga
        routeBogotaBucaramanga = Route.builder()
                .code("BOG-BGA")
                .name("Bogotá - Bucaramanga")
                .origin("Bogotá")
                .destination("Bucaramanga")
                .distanceKm(new BigDecimal("398.50"))
                .durationMin(420)
                .isActive(true)
                .build();

        // Ruta activa: Bogotá → Medellín
        routeBogotaMedellin = Route.builder()
                .code("BOG-MDE")
                .name("Bogotá - Medellín")
                .origin("Bogotá")
                .destination("Medellín")
                .distanceKm(new BigDecimal("415.00"))
                .durationMin(480)
                .isActive(true)
                .build();

        // Ruta activa: Medellín → Cartagena
        routeMedellinCartagena = Route.builder()
                .code("MDE-CTG")
                .name("Medellín - Cartagena")
                .origin("Medellín")
                .destination("Cartagena")
                .distanceKm(new BigDecimal("630.00"))
                .durationMin(720)
                .isActive(true)
                .build();

        // Ruta inactiva
        inactiveRoute = Route.builder()
                .code("BOG-PAS")
                .name("Bogotá - Pasto")
                .origin("Bogotá")
                .destination("Pasto")
                .distanceKm(new BigDecimal("780.00"))
                .durationMin(900)
                .isActive(false)
                .build();

        entityManager.persist(routeBogotaBucaramanga);
        entityManager.persist(routeBogotaMedellin);
        entityManager.persist(routeMedellinCartagena);
        entityManager.persist(inactiveRoute);
        entityManager.flush();

        // Crear paradas para routeBogotaBucaramanga
        Stop stopBogota = Stop.builder()
                .route(routeBogotaBucaramanga)
                .name("Terminal Bogotá")
                .order(1)
                .latitude(new BigDecimal("4.6097"))
                .longitude(new BigDecimal("-74.0817"))
                .build();

        Stop stopTunja = Stop.builder()
                .route(routeBogotaBucaramanga)
                .name("Terminal Tunja")
                .order(2)
                .latitude(new BigDecimal("5.5353"))
                .longitude(new BigDecimal("-73.3678"))
                .build();

        Stop stopBucaramanga = Stop.builder()
                .route(routeBogotaBucaramanga)
                .name("Terminal Bucaramanga")
                .order(3)
                .latitude(new BigDecimal("7.1193"))
                .longitude(new BigDecimal("-73.1227"))
                .build();

        entityManager.persist(stopBogota);
        entityManager.persist(stopTunja);
        entityManager.persist(stopBucaramanga);
        entityManager.flush();
    }

    @Test
    @DisplayName("Debe encontrar todas las rutas activas")
    void shouldFindAllActiveRoutes() {
        // When
        List<Route> activeRoutes = routeRepository.findByIsActiveTrue();

        // Then
        assertThat(activeRoutes).hasSize(3);
        assertThat(activeRoutes).extracting(Route::getCode)
                .containsExactlyInAnyOrder("BOG-BGA", "BOG-MDE", "MDE-CTG");
        assertThat(activeRoutes).allMatch(Route::getIsActive);
    }

    @Test
    @DisplayName("Debe encontrar ruta por código")
    void shouldFindRouteByCode() {
        // When
        Optional<Route> result = routeRepository.findByCode("BOG-BGA");

        // Then
        assertThat(result).isPresent();
        assertThat(result.get().getName()).isEqualTo("Bogotá - Bucaramanga");
        assertThat(result.get().getOrigin()).isEqualTo("Bogotá");
        assertThat(result.get().getDestination()).isEqualTo("Bucaramanga");
    }

    @Test
    @DisplayName("Debe retornar Optional vacío cuando el código no existe")
    void shouldReturnEmptyWhenCodeNotFound() {
        // When
        Optional<Route> result = routeRepository.findByCode("XXX-YYY");

        // Then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Debe encontrar rutas activas por origen y destino")
    void shouldFindActiveRoutesByOriginAndDestination() {
        // When
        List<Route> routes = routeRepository.findByOriginAndDestinationAndIsActiveTrue("Bogotá", "Bucaramanga");

        // Then
        assertThat(routes).hasSize(1);
        assertThat(routes.get(0).getCode()).isEqualTo("BOG-BGA");
    }

    @Test
    @DisplayName("Debe encontrar rutas activas por origen solamente")
    void shouldFindActiveRoutesByOrigin() {
        // When
        List<Route> routes = routeRepository.findByOriginAndIsActiveTrue("Bogotá");

        // Then
        assertThat(routes).hasSize(2);
        assertThat(routes).extracting(Route::getCode)
                .containsExactlyInAnyOrder("BOG-BGA", "BOG-MDE");
    }

    @Test
    @DisplayName("Debe encontrar rutas activas por destino solamente")
    void shouldFindActiveRoutesByDestination() {
        // When
        List<Route> routes = routeRepository.findByDestinationAndIsActiveTrue("Cartagena");

        // Then
        assertThat(routes).hasSize(1);
        assertThat(routes.get(0).getCode()).isEqualTo("MDE-CTG");
    }

    @Test
    @DisplayName("Debe obtener ruta con paradas (fetch join)")
    void shouldFindRouteByIdWithStops() {
        // Limpiar contexto para forzar uso de query con fetch join
        entityManager.clear();
        
        // When
        Optional<Route> result = routeRepository.findByIdWithStops(routeBogotaBucaramanga.getId());

        // Then
        assertThat(result).isPresent();
        Route route = result.get();
        assertThat(route.getStops()).isNotNull();
        assertThat(route.getStops()).hasSize(3);
        assertThat(route.getStops()).extracting(Stop::getName)
                .containsExactly("Terminal Bogotá", "Terminal Tunja", "Terminal Bucaramanga");
        assertThat(route.getStops()).isSortedAccordingTo((s1, s2) -> s1.getOrder().compareTo(s2.getOrder()));
    }

    @Test
    @DisplayName("Debe retornar lista vacía cuando no hay rutas activas para el origen y destino")
    void shouldReturnEmptyListWhenNoActiveRoutesFound() {
        // When
        List<Route> routes = routeRepository.findByOriginAndDestinationAndIsActiveTrue("Cali", "Barranquilla");

        // Then
        assertThat(routes).isEmpty();
    }

    @Test
    @DisplayName("No debe incluir rutas inactivas en búsqueda por origen")
    void shouldNotIncludeInactiveRoutesInOriginSearch() {
        // When
        List<Route> routes = routeRepository.findByOriginAndIsActiveTrue("Bogotá");

        // Then
        assertThat(routes).hasSize(2);
        assertThat(routes).extracting(Route::getCode)
                .doesNotContain("BOG-PAS"); // Ruta inactiva no debe aparecer
    }
}
