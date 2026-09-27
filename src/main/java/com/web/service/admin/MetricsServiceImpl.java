package com.web.service.admin;

import com.web.dto.admin.MetricsResponse;
import com.web.dto.admin.OccupancyMetrics;
import com.web.dto.admin.OperationalMetrics;
import com.web.dto.admin.ParcelMetrics;
import com.web.dto.admin.RevenueMetrics;
import com.web.entity.Parcel;
import com.web.entity.Ticket;
import com.web.entity.Trip;
import com.web.exception.BusinessException;
import com.web.repository.IncidentRepository;
import com.web.repository.ParcelRepository;
import com.web.repository.TicketRepository;
import com.web.repository.TripRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * KPIs del panel de administración (GET /admin/metrics): ocupación por viaje (promedio, p50, p95),
 * ingresos por método de pago y canal, puntualidad, no-show, cancelaciones e incidentes,
 * y encomiendas entregadas vs fallidas por tramo.
 */
@Service
@RequiredArgsConstructor
public class MetricsServiceImpl implements MetricsService {

    // Tolerancias para considerar puntual una salida o una llegada
    static final long ON_TIME_DEPARTURE_TOLERANCE_MINUTES = 5;
    static final long ON_TIME_ARRIVAL_TOLERANCE_MINUTES = 10;

    private final TripRepository tripRepository;
    private final TicketRepository ticketRepository;
    private final ParcelRepository parcelRepository;
    private final IncidentRepository incidentRepository;

    @Override
    @Transactional(readOnly = true)
    public MetricsResponse getMetrics(LocalDate startDate, LocalDate endDate) {
        if (endDate.isBefore(startDate)) {
            throw new BusinessException("La fecha final no puede ser anterior a la inicial",
                    HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE");
        }

        List<Trip> trips = tripRepository.findByDateRange(startDate, endDate);
        List<Ticket> tickets = ticketRepository.findByTripDateBetween(startDate, endDate);
        List<Parcel> parcels = parcelRepository.findByDateRange(startDate, endDate);
        long incidents = incidentRepository.countByCreatedAtBetween(
                startDate.atStartOfDay(), endDate.plusDays(1).atStartOfDay());

        return new MetricsResponse(
                occupancy(trips, tickets),
                revenue(tickets, parcels),
                operational(trips, tickets, incidents),
                parcels(parcels));
    }

    private OccupancyMetrics occupancy(List<Trip> trips, List<Ticket> tickets) {
        // Sillas distintas ocupadas (vendidas o no-show) por viaje
        Map<Long, Long> seatsByTrip = tickets.stream()
                .filter(t -> t.getStatus() != Ticket.TicketStatus.CANCELLED)
                .collect(Collectors.groupingBy(t -> t.getTrip().getId(),
                        Collectors.mapping(Ticket::getSeatNumber, Collectors.collectingAndThen(
                                Collectors.toSet(), set -> (long) set.size()))));

        List<Double> rates = new ArrayList<>();
        for (Trip trip : trips) {
            if (trip.getStatus() == Trip.TripStatus.CANCELLED || trip.getBus().getCapacity() <= 0) {
                continue;
            }
            long seats = seatsByTrip.getOrDefault(trip.getId(), 0L);
            rates.add(seats * 100.0 / trip.getBus().getCapacity());
        }
        Collections.sort(rates);

        int seatsSold = (int) tickets.stream().filter(t -> t.getStatus() == Ticket.TicketStatus.SOLD).count();
        double average = rates.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);

        return new OccupancyMetrics(average, percentile(rates, 50), percentile(rates, 95), rates.size(), seatsSold);
    }

    private RevenueMetrics revenue(List<Ticket> tickets, List<Parcel> parcels) {
        Map<String, BigDecimal> byPaymentMethod = new TreeMap<>();
        Map<String, BigDecimal> byChannel = new TreeMap<>();
        BigDecimal ticketRevenue = BigDecimal.ZERO;
        BigDecimal baggageRevenue = BigDecimal.ZERO;
        BigDecimal noShowFees = BigDecimal.ZERO;

        for (Ticket ticket : tickets) {
            // Un ticket cancelado aporta lo que no se reembolsó
            BigDecimal net = ticket.getPrice().subtract(
                    ticket.getStatus() == Ticket.TicketStatus.CANCELLED && ticket.getRefundAmount() != null
                            ? ticket.getRefundAmount() : BigDecimal.ZERO);
            ticketRevenue = ticketRevenue.add(net);
            byPaymentMethod.merge(ticket.getPaymentMethod().name(), net, BigDecimal::add);
            byChannel.merge(ticket.getChannel() == null ? Ticket.SalesChannel.APP.name() : ticket.getChannel().name(),
                    net, BigDecimal::add);

            if (ticket.getStatus() != Ticket.TicketStatus.CANCELLED
                    && ticket.getBaggage() != null && ticket.getBaggage().getExcessFee() != null) {
                baggageRevenue = baggageRevenue.add(ticket.getBaggage().getExcessFee());
            }
            if (ticket.getNoShowFee() != null) {
                noShowFees = noShowFees.add(ticket.getNoShowFee());
            }
        }

        BigDecimal parcelRevenue = parcels.stream()
                .map(Parcel::getPrice)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal total = ticketRevenue.add(baggageRevenue).add(noShowFees).add(parcelRevenue);
        return new RevenueMetrics(total, ticketRevenue, parcelRevenue, baggageRevenue, noShowFees,
                byPaymentMethod, byChannel);
    }

    private OperationalMetrics operational(List<Trip> trips, List<Ticket> tickets, long incidents) {
        List<Trip> departed = trips.stream().filter(t -> t.getDepartedAt() != null).toList();
        List<Trip> arrived = trips.stream()
                .filter(t -> t.getArrivedAt() != null && t.getArrivalEta() != null).toList();

        Double onTimeDeparture = departed.isEmpty() ? null : departed.stream()
                .filter(t -> !t.getDepartedAt().isAfter(t.getDepartureTime().plusMinutes(ON_TIME_DEPARTURE_TOLERANCE_MINUTES)))
                .count() * 100.0 / departed.size();
        Double onTimeArrival = arrived.isEmpty() ? null : arrived.stream()
                .filter(t -> !t.getArrivedAt().isAfter(t.getArrivalEta().plusMinutes(ON_TIME_ARRIVAL_TOLERANCE_MINUTES)))
                .count() * 100.0 / arrived.size();

        long noShows = tickets.stream().filter(t -> t.getStatus() == Ticket.TicketStatus.NO_SHOW).count();
        long sold = tickets.stream().filter(t -> t.getStatus() == Ticket.TicketStatus.SOLD).count();
        long cancellations = tickets.stream().filter(t -> t.getStatus() == Ticket.TicketStatus.CANCELLED).count();
        double noShowRate = (noShows + sold) == 0 ? 0.0 : noShows * 100.0 / (noShows + sold);

        return new OperationalMetrics(onTimeDeparture, onTimeArrival, noShowRate,
                (int) cancellations, (int) incidents, (int) noShows);
    }

    private ParcelMetrics parcels(List<Parcel> parcels) {
        int delivered = (int) parcels.stream().filter(p -> p.getStatus() == Parcel.ParcelStatus.DELIVERED).count();
        int failed = (int) parcels.stream().filter(p -> p.getStatus() == Parcel.ParcelStatus.FAILED).count();
        double successRate = (delivered + failed) == 0 ? 0.0 : delivered * 100.0 / (delivered + failed);

        Map<String, Integer> byRoute = new TreeMap<>();
        Map<String, Integer> deliveredBySegment = new TreeMap<>();
        Map<String, Integer> failedBySegment = new TreeMap<>();
        for (Parcel parcel : parcels) {
            byRoute.merge(parcel.getTrip().getRoute().getName(), 1, Integer::sum);
            String segment = parcel.getFromStop().getName() + " → " + parcel.getToStop().getName();
            if (parcel.getStatus() == Parcel.ParcelStatus.DELIVERED) {
                deliveredBySegment.merge(segment, 1, Integer::sum);
            } else if (parcel.getStatus() == Parcel.ParcelStatus.FAILED) {
                failedBySegment.merge(segment, 1, Integer::sum);
            }
        }

        return new ParcelMetrics(parcels.size(), delivered, failed, successRate, byRoute,
                deliveredBySegment, failedBySegment);
    }

    // Percentil por el método del rango más cercano sobre una lista ordenada
    static Double percentile(List<Double> sorted, int percentile) {
        if (sorted.isEmpty()) {
            return 0.0;
        }
        int rank = (int) Math.ceil(percentile / 100.0 * sorted.size());
        return sorted.get(Math.max(0, rank - 1));
    }
}
