package com.web.repository;

import com.web.entity.Route;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RouteRepository extends JpaRepository<Route, Long> {

    // Buscar todas las rutas activas
    List<Route> findByIsActiveTrue();

    // Buscar ruta por código
    Optional<Route> findByCode(String code);

    // Verificar si existe código
    boolean existsByCode(String code);

    // Buscar rutas por origen y/o destino
    List<Route> findByOriginAndDestinationAndIsActiveTrue(String origin, String destination);

    List<Route> findByOriginAndIsActiveTrue(String origin);

    List<Route> findByDestinationAndIsActiveTrue(String destination);

    // Obtener ruta con paradas
    @Query("""
                SELECT DISTINCT r FROM Route r
                LEFT JOIN FETCH r.stops s
                WHERE r.id = :routeId
                ORDER BY s.order
            """)
    Optional<Route> findByIdWithStops(@Param("routeId") Long routeId);

    // Buscar rutas que conectan dos ciudades (por stops)
    @Query("""
                SELECT DISTINCT r FROM Route r
                JOIN r.stops s1
                JOIN r.stops s2
                WHERE LOWER(s1.name) LIKE LOWER(CONCAT('%', :origin, '%'))
                AND LOWER(s2.name) LIKE LOWER(CONCAT('%', :destination, '%'))
                AND s1.order < s2.order
                AND r.isActive = true
            """)
    List<Route> findRoutesConnecting(
            @Param("origin") String origin,
            @Param("destination") String destination);
}