package com.web.dto.admin;

import java.io.Serializable;

// Analisis operativo
public record OperationalMetrics(
        Double onTimeDepartureRate, // Tasa de salida puntual
        Double onTimeArrivalRate,
        Double noShowRate,
        Integer totalCancellations,
        Integer totalIncidents,
        Integer totalNoShows) implements Serializable {
}
