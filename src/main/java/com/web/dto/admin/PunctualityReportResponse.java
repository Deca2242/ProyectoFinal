package com.web.dto.admin;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

// Reporte diario de puntualidad por ruta persistido por el job nocturno
public record PunctualityReportResponse(
        LocalDate reportDate,
        Long routeId,
        String routeCode,
        Integer trips,
        Double onTimeDeparturePct,
        Double onTimeArrivalPct,
        Double avgDepartureDelayMin,
        Double avgArrivalDelayMin,
        LocalDateTime createdAt) implements Serializable {
}
