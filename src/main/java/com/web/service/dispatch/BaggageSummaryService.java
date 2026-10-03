package com.web.service.dispatch;

import com.web.dto.baggage.TripBaggageSummaryResponse;

public interface BaggageSummaryService {

    TripBaggageSummaryResponse getTripBaggage(Long tripId);
}
