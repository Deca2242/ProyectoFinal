package com.web.service.trip;

import com.web.dto.ticket.TicketResponse;
import com.web.dto.trip.SeatAvailabilityResponse;
import com.web.dto.trip.SegmentOccupancyResponse;
import com.web.dto.trip.TripCreateRequest;
import com.web.dto.trip.TripDetailResponse;
import com.web.dto.trip.TripResponse;
import com.web.dto.trip.TripUpdateRequest;
import com.web.entity.Trip;

import java.time.LocalDate;
import java.util.List;

public interface TripService {
    
    TripResponse createTrip(TripCreateRequest request);
    
    // includeAll solo tiene efecto para ADMIN/DISPATCHER; el resto solo ve salidas reservables
    List<TripResponse> searchTrips(Long routeId, LocalDate date, boolean includeAll);
    
    TripDetailResponse getTripById(Long id);
    
    SeatAvailabilityResponse getSeatAvailability(Long tripId, Long fromStopId, Long toStopId);

    List<SegmentOccupancyResponse> getOccupancyBySegment(Long tripId);
    
    TripResponse updateTripStatus(Long id, Trip.TripStatus status);
    
    void cancelTrip(Long id);
    
    List<TicketResponse> getPassengersBySegment(Long tripId, Long fromStopId, Long toStopId);

    TripResponse rescheduleTrip(Long id, TripUpdateRequest request);
}
