package com.web.service.admin;

import com.web.dto.admin.PunctualityReportResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

// Reporte diario de puntualidad: a las 00:05 calcula y persiste la puntualidad por ruta del día anterior.
// Un viaje que aún no ha llegado a esa hora solo cuenta para la puntualidad de salida
@Slf4j
@Component
@RequiredArgsConstructor
public class PunctualityReportScheduler {

    private final MetricsService metricsService;

    @Scheduled(cron = "0 5 0 * * *")
    public void generateDailyReport() {
        LocalDate reportDate = LocalDate.now().minusDays(1);
        List<PunctualityReportResponse> reports = metricsService.generatePunctualityReport(reportDate);
        log.info("Reporte de puntualidad del {} generado para {} ruta(s)", reportDate, reports.size());
    }
}
