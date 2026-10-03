package com.web.dto.trip;

import com.web.dto.catalog.Bus.BusResponse;
import com.web.dto.catalog.Route.RouteResponse;
import com.web.dto.dispatch.Assignment.AssignmentResponse;
import com.web.entity.Trip;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

// Más info que TripResponse
public record TripDetailResponse(
        Long id,
        RouteResponse route,
        BusResponse bus,
        LocalDate tripDate,
        LocalDateTime departureTime,
        LocalDateTime arrivalEta,
        Trip.TripStatus status,
        AssignmentResponse assignment, // Puede ser null
        Integer soldSeats,
        Integer availableSeats,
        Double occupancyPercentage,
        List<Integer> availableSeatNumbers, // Sillas libres para el viaje completo (sin venta en ningún tramo)
        String platform,
        LocalDateTime departedAt,
        LocalDateTime arrivedAt) implements Serializable {
}
