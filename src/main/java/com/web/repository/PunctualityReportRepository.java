package com.web.repository;

import com.web.entity.PunctualityReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface PunctualityReportRepository extends JpaRepository<PunctualityReport, Long> {

    // Regenerar el reporte de un día actualiza la fila existente (UNIQUE report_date, route_id)
    Optional<PunctualityReport> findByReportDateAndRouteId(LocalDate reportDate, Long routeId);

    // Reportes de un rango de fechas, ordenados por fecha y código de ruta
    @Query("""
        SELECT r FROM PunctualityReport r
        JOIN FETCH r.route
        WHERE r.reportDate BETWEEN :from AND :to
        ORDER BY r.reportDate, r.route.code
    """)
    List<PunctualityReport> findByReportDateBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
