package com.web.repository;

import com.web.entity.Stop;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface StopRepository extends JpaRepository<Stop, Long> {

    // Buscar paradas de una ruta ordenadas por secuencia
    List<Stop> findByRouteIdOrderByOrderAsc(Long routeId);

    // Validar si una parada pertenece a una ruta
    boolean existsByIdAndRouteId(Long stopId, Long routeId);

    // Validar order único dentro de una ruta
    boolean existsByRouteIdAndOrder(Long routeId, Integer order);

    // Obtener el orden de una parada para validación de tramos
    @Query("""
                SELECT s.order FROM Stop s
                WHERE s.id = :stopId
            """)
    Integer getStopOrder(@Param("stopId") Long stopId);

    // Validar que un tramo sea válido
    @Query("""
                SELECT CASE WHEN COUNT(*) > 0 THEN true ELSE false END
                FROM Stop fs, Stop ts
                WHERE fs.id = :fromStopId
                AND ts.id = :toStopId
                AND fs.route.id = ts.route.id
                AND fs.order < ts.order
            """)
    Boolean isValidSegment(
            @Param("fromStopId") Long fromStopId,
            @Param("toStopId") Long toStopId);

    // La parada está referenciada por tickets, encomiendas, tarifas u holds (no se puede borrar)
    @Query(value = """
            SELECT EXISTS (SELECT 1 FROM tickets WHERE from_stop_id = :stopId OR to_stop_id = :stopId)
                OR EXISTS (SELECT 1 FROM parcels WHERE from_stop_id = :stopId OR to_stop_id = :stopId)
                OR EXISTS (SELECT 1 FROM fare_rules WHERE from_stop_id = :stopId OR to_stop_id = :stopId)
                OR EXISTS (SELECT 1 FROM seat_holds WHERE from_stop_id = :stopId OR to_stop_id = :stopId)
            """, nativeQuery = true)
    boolean isReferenced(@Param("stopId") Long stopId);
}
