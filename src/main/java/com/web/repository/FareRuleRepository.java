package com.web.repository;

import com.web.entity.FareRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface FareRuleRepository extends JpaRepository<FareRule, Long> {

    // Buscar regla de tarifa para un tramo específico
    Optional<FareRule> findByRouteIdAndFromStopIdAndToStopId(
            Long routeId,
            Long fromStopId,
            Long toStopId);

    // Buscar todas las reglas de tarifa de una ruta
    List<FareRule> findByRouteId(Long routeId);

    // Buscar reglas de tarifa con precios dinámicos habilitados
    @Query("""
                SELECT f FROM FareRule f
                WHERE f.route.id = :routeId
                AND f.dynamicPricingEnabled = true
            """)
    List<FareRule> findDynamicPricingRules(@Param("routeId") Long routeId);

    // Obtener precio base para un tramo
    @Query("""
                SELECT f.basePrice FROM FareRule f
                WHERE f.route.id = :routeId
                AND f.fromStop.id = :fromStopId
                AND f.toStop.id = :toStopId
            """)
    BigDecimal getBasePrice(
            @Param("routeId") Long routeId,
            @Param("fromStopId") Long fromStopId,
            @Param("toStopId") Long toStopId);

    // Buscar todas las reglas de tarifa para tramos entre dos paradas
    @Query("""
                SELECT f FROM FareRule f
                WHERE f.route.id = :routeId
                AND f.fromStop.order >= :fromStopOrder
                AND f.toStop.order <= :toStopOrder
                ORDER BY f.fromStop.order, f.toStop.order
            """)
    List<FareRule> findFareRulesInRange(
            @Param("routeId") Long routeId,
            @Param("fromStopOrder") Integer fromStopOrder,
            @Param("toStopOrder") Integer toStopOrder);
}