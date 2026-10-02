package com.web.repository;

import com.web.entity.Incident;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

// JpaSpecificationExecutor: búsqueda de incidentes con filtros opcionales
public interface IncidentRepository extends JpaRepository<Incident, Long>, JpaSpecificationExecutor<Incident> {

    // Métricas: incidentes registrados en un rango de tiempo
    long countByCreatedAtBetween(LocalDateTime start, LocalDateTime end);

    // Buscar incidentes por entidad
    List<Incident> findByEntityTypeAndEntityId(
            Incident.EntityType entityType,
            Long entityId);

    // Buscar incidentes por tipo
    List<Incident> findByIncidentType(Incident.IncidentType incidentType);

    // Buscar incidentes por reportador
    List<Incident> findByReportedById(Long userId);

    // Buscar incidentes recientes
    @Query("""
                SELECT i FROM Incident i
                WHERE i.createdAt >= :fromDate
                ORDER BY i.createdAt DESC
            """)
    List<Incident> findRecentIncidents(@Param("fromDate") LocalDateTime fromDate);

    // Buscar incidentes por viaje
    @Query("""
                SELECT i FROM Incident i
                WHERE i.entityType = 'TRIP'
                AND i.entityId = :tripId
                ORDER BY i.createdAt DESC
            """)
    List<Incident> findIncidentsByTrip(@Param("tripId") Long tripId);

    // Buscar fallos de entrega de encomiendas
    @Query("""
                SELECT i FROM Incident i
                WHERE i.entityType = 'PARCEL'
                AND i.incidentType = 'DELIVERY_FAIL'
                AND i.createdAt >= :fromDate
                ORDER BY i.createdAt DESC
            """)
    List<Incident> findParcelDeliveryFailures(@Param("fromDate") LocalDateTime fromDate);

    // Contar incidentes por tipo en rango de fechas
    @Query("""
                SELECT i.incidentType, COUNT(i)
                FROM Incident i
                WHERE i.createdAt BETWEEN :startDate AND :endDate
                GROUP BY i.incidentType
            """)
    List<Object[]> countIncidentsByType(
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate);
}