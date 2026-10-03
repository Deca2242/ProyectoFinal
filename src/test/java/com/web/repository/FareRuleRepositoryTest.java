package com.web.repository;

import com.web.entity.FareRule;
import com.web.entity.Route;
import com.web.entity.Stop;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;


@DisplayName("FareRuleRepository Integration Tests")
class FareRuleRepositoryTest extends BaseRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private FareRuleRepository fareRuleRepository;

    private Route route;
    private Stop stopBogota;
    private Stop stopTunja;
    private Stop stopBucaramanga;
    private FareRule fareBogotaTunja;
    private FareRule fareTunjaBucaramanga;
    private FareRule fareBogotaBucaramanga;

    @BeforeEach
    void setUp() {
        entityManager.clear();

        // Crear ruta
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

        // Crear paradas
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

        // Crear reglas de tarifa
        Map<String, Object> discounts = new HashMap<>();
        discounts.put("student", 0.15);
        discounts.put("senior", 0.20);
        discounts.put("child", 0.30);

        fareBogotaTunja = FareRule.builder()
                .route(route)
                .fromStop(stopBogota)
                .toStop(stopTunja)
                .basePrice(new BigDecimal("25000.00"))
                .discounts(discounts)
                .dynamicPricingEnabled(false)
                .build();

        fareTunjaBucaramanga = FareRule.builder()
                .route(route)
                .fromStop(stopTunja)
                .toStop(stopBucaramanga)
                .basePrice(new BigDecimal("30000.00"))
                .discounts(discounts)
                .dynamicPricingEnabled(true)
                .build();

        fareBogotaBucaramanga = FareRule.builder()
                .route(route)
                .fromStop(stopBogota)
                .toStop(stopBucaramanga)
                .basePrice(new BigDecimal("50000.00"))
                .discounts(discounts)
                .dynamicPricingEnabled(true)
                .build();

        entityManager.persist(fareBogotaTunja);
        entityManager.persist(fareTunjaBucaramanga);
        entityManager.persist(fareBogotaBucaramanga);
        entityManager.flush();
    }

    @Test
    @DisplayName("Debe encontrar regla de tarifa para un tramo específico")
    void shouldFindFareRuleByRouteAndStops() {
        // When
        Optional<FareRule> result = fareRuleRepository.findByRouteIdAndFromStopIdAndToStopId(
                route.getId(),
                stopBogota.getId(),
                stopTunja.getId()
        );

        // Then
        assertThat(result).isPresent();
        assertThat(result.get().getBasePrice()).isEqualByComparingTo(new BigDecimal("25000.00"));
        assertThat(result.get().getDynamicPricingEnabled()).isFalse();
    }

    @Test
    @DisplayName("Debe retornar Optional vacío cuando no existe regla para el tramo")
    void shouldReturnEmptyWhenFareRuleNotFound() {
        // When - tramo inverso que no existe
        Optional<FareRule> result = fareRuleRepository.findByRouteIdAndFromStopIdAndToStopId(
                route.getId(),
                stopTunja.getId(),
                stopBogota.getId()
        );

        // Then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Debe encontrar todas las reglas de tarifa de una ruta")
    void shouldFindAllFareRulesByRoute() {
        // When
        List<FareRule> fareRules = fareRuleRepository.findByRouteId(route.getId());

        // Then
        assertThat(fareRules).hasSize(3);
        assertThat(fareRules).extracting(FareRule::getBasePrice)
                .containsExactlyInAnyOrder(
                        new BigDecimal("25000.00"),
                        new BigDecimal("30000.00"),
                        new BigDecimal("50000.00")
                );
    }

    @Test
    @DisplayName("Debe encontrar reglas con precios dinámicos habilitados")
    void shouldFindDynamicPricingRules() {
        // When
        List<FareRule> dynamicRules = fareRuleRepository.findDynamicPricingRules(route.getId());

        // Then
        assertThat(dynamicRules).hasSize(2);
        assertThat(dynamicRules).allMatch(FareRule::getDynamicPricingEnabled);
        assertThat(dynamicRules).extracting(FareRule::getBasePrice)
                .containsExactlyInAnyOrder(
                        new BigDecimal("30000.00"),
                        new BigDecimal("50000.00")
                );
    }

    @Test
    @DisplayName("Debe obtener precio base para un tramo")
    void shouldGetBasePriceForSegment() {
        // When
        BigDecimal price = fareRuleRepository.getBasePrice(
                route.getId(),
                stopBogota.getId(),
                stopBucaramanga.getId()
        );

        // Then
        assertThat(price).isEqualByComparingTo(new BigDecimal("50000.00"));
    }

    @Test
    @DisplayName("Debe encontrar reglas de tarifa en un rango de paradas")
    void shouldFindFareRulesInRange() {
        // When - buscar reglas entre orden 1 (Bogotá) y 3 (Bucaramanga)
        List<FareRule> fareRules = fareRuleRepository.findFareRulesInRange(
                route.getId(),
                1, // fromStopOrder
                3  // toStopOrder
        );

        // Then - debería encontrar todas las reglas que inician y terminan en ese rango
        assertThat(fareRules).hasSize(3);
    }

    @Test
    @DisplayName("Debe filtrar reglas fuera del rango de paradas")
    void shouldFilterFareRulesOutsideRange() {
        // When - buscar reglas solo entre orden 1 y 2
        List<FareRule> fareRules = fareRuleRepository.findFareRulesInRange(
                route.getId(),
                1, // fromStopOrder (Bogotá)
                2  // toStopOrder (Tunja)
        );

        // Then - solo debería encontrar la regla Bogotá -> Tunja
        assertThat(fareRules).hasSize(1);
        assertThat(fareRules.get(0).getBasePrice()).isEqualByComparingTo(new BigDecimal("25000.00"));
    }

    @Test
    @DisplayName("Debe retornar lista vacía cuando no hay reglas para la ruta")
    void shouldReturnEmptyListWhenNoFareRulesForRoute() {
        // Given - crear ruta sin reglas de tarifa
        Route newRoute = Route.builder()
                .code("MDE-CTG")
                .name("Medellín - Cartagena")
                .origin("Medellín")
                .destination("Cartagena")
                .distanceKm(new BigDecimal("630.00"))
                .durationMin(720)
                .isActive(true)
                .build();
        entityManager.persist(newRoute);
        entityManager.flush();

        // When
        List<FareRule> fareRules = fareRuleRepository.findByRouteId(newRoute.getId());

        // Then
        assertThat(fareRules).isEmpty();
    }

    @Test
    @DisplayName("Debe retornar lista vacía cuando no hay reglas dinámicas")
    void shouldReturnEmptyListWhenNoDynamicPricingRules() {
        // Given - crear ruta con reglas pero sin pricing dinámico
        Route newRoute = Route.builder()
                .code("CAL-BAQ")
                .name("Cali - Barranquilla")
                .origin("Cali")
                .destination("Barranquilla")
                .distanceKm(new BigDecimal("1050.00"))
                .durationMin(900)
                .isActive(true)
                .build();
        entityManager.persist(newRoute);

        Stop stop1 = Stop.builder()
                .route(newRoute)
                .name("Terminal Cali")
                .order(1)
                .latitude(new BigDecimal("3.4516"))
                .longitude(new BigDecimal("-76.5320"))
                .build();

        Stop stop2 = Stop.builder()
                .route(newRoute)
                .name("Terminal Barranquilla")
                .order(2)
                .latitude(new BigDecimal("10.9639"))
                .longitude(new BigDecimal("-74.7964"))
                .build();

        entityManager.persist(stop1);
        entityManager.persist(stop2);

        FareRule staticFare = FareRule.builder()
                .route(newRoute)
                .fromStop(stop1)
                .toStop(stop2)
                .basePrice(new BigDecimal("80000.00"))
                .discounts(new HashMap<>())
                .dynamicPricingEnabled(false)
                .build();

        entityManager.persist(staticFare);
        entityManager.flush();

        // When
        List<FareRule> dynamicRules = fareRuleRepository.findDynamicPricingRules(newRoute.getId());

        // Then
        assertThat(dynamicRules).isEmpty();
    }
}
