package com.web.service.admin;

import com.web.dto.admin.MetricsResponse;
import com.web.dto.admin.PunctualityReportResponse;

import java.time.LocalDate;
import java.util.List;

public interface MetricsService {

    MetricsResponse getMetrics(LocalDate startDate, LocalDate endDate);

    // Calcula y persiste la puntualidad por ruta de un día (regenerarlo actualiza los registros existentes)
    List<PunctualityReportResponse> generatePunctualityReport(LocalDate date);

    // Reportes diarios de puntualidad ya persistidos en un rango de fechas
    List<PunctualityReportResponse> getPunctualityReports(LocalDate from, LocalDate to);
}
