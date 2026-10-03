package com.web.dto.admin;

import java.io.Serializable;

// Puntualidad de una ruta en el rango consultado
public record RoutePunctuality(
        Long routeId,
        String routeCode,
        Integer trips, // Viajes que salieron
        Double onTimeDeparturePct,
        Double onTimeArrivalPct, // null si ninguno ha llegado
        Double avgDepartureDelayMin) implements Serializable {
}
