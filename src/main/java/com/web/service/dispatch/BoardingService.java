package com.web.service.dispatch;

import com.web.dto.trip.TripResponse;

public interface BoardingService {
    
    TripResponse openBoarding(Long tripId);
    
    TripResponse closeBoarding(Long tripId);
    
    TripResponse departTrip(Long tripId);

    TripResponse arriveTrip(Long tripId);
    
    TripResponse getTripStatus(Long tripId);
}

