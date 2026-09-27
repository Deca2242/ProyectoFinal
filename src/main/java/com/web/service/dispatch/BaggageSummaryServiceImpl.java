package com.web.service.dispatch;

import com.web.dto.baggage.TripBaggageSummaryResponse;
import com.web.dto.baggage.mapper.BaggageMapper;
import com.web.entity.Baggage;
import com.web.entity.Ticket;
import com.web.exception.ResourceNotFoundException;
import com.web.repository.BaggageRepository;
import com.web.repository.TripRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

// Conteo de equipaje por viaje (etiqueta y conteo por maletero): solo cuenta el de tickets vigentes
@Service
@RequiredArgsConstructor
public class BaggageSummaryServiceImpl implements BaggageSummaryService {

    private final TripRepository tripRepository;
    private final BaggageRepository baggageRepository;
    private final BaggageMapper baggageMapper;

    @Override
    @Transactional(readOnly = true)
    public TripBaggageSummaryResponse getTripBaggage(Long tripId) {
        if (!tripRepository.existsById(tripId)) {
            throw new ResourceNotFoundException("Viaje", tripId);
        }

        List<Baggage> baggage = baggageRepository.findByTripId(tripId).stream()
                .filter(b -> b.getTicket().getStatus() == Ticket.TicketStatus.SOLD)
                .toList();

        BigDecimal totalWeight = baggage.stream()
                .map(Baggage::getWeightKg)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalExcessFee = baggage.stream()
                .map(b -> b.getExcessFee() == null ? BigDecimal.ZERO : b.getExcessFee())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new TripBaggageSummaryResponse(
                tripId,
                baggage.size(),
                totalWeight,
                totalExcessFee,
                baggageMapper.toResponseList(baggage));
    }
}
