package com.web.dto.admin;

import java.io.Serializable;

// Analisis de ocupacion
public record OccupancyMetrics(
                Double averageOccupancy, // Ocupacion promedio
                Double p50Occupancy, // Percentil 50 de ocupacion
                Double p95Occupancy,
                Integer totalTrips,
                Integer totalSeatsSold) implements Serializable {
}
