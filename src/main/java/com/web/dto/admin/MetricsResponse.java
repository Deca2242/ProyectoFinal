package com.web.dto.admin;

import java.io.Serializable;

public record MetricsResponse(
        OccupancyMetrics occupancy, // Ocupación promedio, p50, p95, total de viajes, total de asientos vendidos
        RevenueMetrics revenue, // Irngresos totales
        OperationalMetrics operational, // Tasa de puntualidad
        ParcelMetrics parcels // Total de paquetes entregados , fallidos
) implements Serializable {
}
