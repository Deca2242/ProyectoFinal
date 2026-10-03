package com.web.dto.admin;

import java.io.Serializable;
import java.util.List;

// Analisis operativo
public record OperationalMetrics(
        Double onTimeDepartureRate, // Tasa de salida puntual
        Double onTimeArrivalRate,
        Double noShowRate,
        Integer totalCancellations,
        Integer totalIncidents,
        Integer totalNoShows,
        // Retrasos en minutos (una salida o llegada adelantada cuenta como 0); null si no hay viajes medidos
        Double avgDepartureDelayMin,
        Double p95DepartureDelayMin,
        Double avgArrivalDelayMin,
        Double p95ArrivalDelayMin,
        // Desglose de puntualidad por ruta (solo rutas con al menos un viaje que salió)
        List<RoutePunctuality> punctualityByRoute) implements Serializable {
}
