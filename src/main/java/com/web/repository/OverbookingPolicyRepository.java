package com.web.repository;

import com.web.entity.OverbookingPolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface OverbookingPolicyRepository extends JpaRepository<OverbookingPolicy, Long> {

    // Franjas de una ruta ordenadas por hora de inicio
    List<OverbookingPolicy> findByRouteIdOrderByStartHourAsc(Long routeId);

    // Franja de la ruta que contiene la hora de salida (start_hour <= hora < end_hour)
    @Query("""
                SELECT p FROM OverbookingPolicy p
                WHERE p.route.id = :routeId
                AND p.startHour <= :hour
                AND p.endHour > :hour
            """)
    Optional<OverbookingPolicy> findApplicablePolicy(@Param("routeId") Long routeId, @Param("hour") Integer hour);
}
