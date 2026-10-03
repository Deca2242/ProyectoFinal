package com.web.service.admin;

import com.web.dto.admin.MetricsResponse;
import com.web.dto.admin.OccupancyMetrics;
import com.web.dto.admin.OperationalMetrics;
import com.web.dto.admin.ParcelMetrics;
import com.web.dto.admin.PunctualityReportResponse;
import com.web.dto.admin.RevenueMetrics;
import com.web.dto.admin.RoutePunctuality;
import com.web.entity.Parcel;
import com.web.entity.PunctualityReport;
import com.web.entity.Route;
import com.web.entity.Ticket;
import com.web.entity.Trip;
import com.web.exception.BusinessException;
import com.web.repository.IncidentRepository;
import com.web.repository.ParcelRepository;
import com.web.repository.PunctualityReportRepository;
import com.web.repository.TicketRepository;
import com.web.repository.TripRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * KPIs del panel de administración (GET /admin/metrics): ocupación por viaje (promedio, p50, p95),
 * ingresos por método de pago y canal, puntualidad (tasas, retrasos promedio/p95 y desglose por ruta),
 * no-show, cancelaciones e incidentes, y encomiendas entregadas vs fallidas por tramo.
 * También genera y consulta el reporte diario de puntualidad por ruta (PunctualityReportScheduler).
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
    private final PunctualityReportRepository punctualityReportRepository;

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

    // Ocupación de cada viaje = sillas DISTINTAS con ticket no cancelado / capacidad del bus.
    // Es por viaje y no por tramo: una silla vendida en dos tramos que no se solapan (A→B y B→C)
    // cuenta una sola vez, y una vendida solo en A→B cuenta como ocupada todo el recorrido
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
        Punctuality punctuality = punctuality(trips);

        long noShows = tickets.stream().filter(t -> t.getStatus() == Ticket.TicketStatus.NO_SHOW).count();
        long sold = tickets.stream().filter(t -> t.getStatus() == Ticket.TicketStatus.SOLD).count();
        long cancellations = tickets.stream().filter(t -> t.getStatus() == Ticket.TicketStatus.CANCELLED).count();
        double noShowRate = (noShows + sold) == 0 ? 0.0 : noShows * 100.0 / (noShows + sold);

        List<RoutePunctuality> byRoute = departedTripsByRoute(trips).values().stream()
                .map(routeTrips -> {
                    Route route = routeTrips.get(0).getRoute();
                    Punctuality p = punctuality(routeTrips);
                    return new RoutePunctuality(route.getId(), route.getCode(), p.departedTrips(),
                            p.onTimeDeparturePct(), p.onTimeArrivalPct(), p.avgDepartureDelayMin());
                })
                .sorted(Comparator.comparing(RoutePunctuality::routeCode, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        return new OperationalMetrics(punctuality.onTimeDeparturePct(), punctuality.onTimeArrivalPct(), noShowRate,
                (int) cancellations, (int) incidents, (int) noShows,
                punctuality.avgDepartureDelayMin(), punctuality.p95DepartureDelayMin(),
                punctuality.avgArrivalDelayMin(), punctuality.p95ArrivalDelayMin(),
                byRoute);
    }

    // Reporte diario: puntualidad por ruta de los viajes con fecha "date" (se ejecuta para el día anterior)
    @Override
    @Transactional
    public List<PunctualityReportResponse> generatePunctualityReport(LocalDate date) {
        List<PunctualityReportResponse> reports = new ArrayList<>();
        for (List<Trip> routeTrips : departedTripsByRoute(tripRepository.findByDateRange(date, date)).values()) {
            Route route = routeTrips.get(0).getRoute();
            Punctuality p = punctuality(routeTrips);

            // UNIQUE (report_date, route_id): regenerar el mismo día actualiza la fila
            PunctualityReport report = punctualityReportRepository.findByReportDateAndRouteId(date, route.getId())
                    .orElseGet(() -> PunctualityReport.builder().reportDate(date).route(route).build());
            report.setTrips(p.departedTrips());
            report.setOnTimeDeparturePct(p.onTimeDeparturePct());
            report.setOnTimeArrivalPct(p.onTimeArrivalPct());
            report.setAvgDepartureDelayMin(p.avgDepartureDelayMin());
            report.setAvgArrivalDelayMin(p.avgArrivalDelayMin());
            report.setCreatedAt(LocalDateTime.now());
            reports.add(toResponse(punctualityReportRepository.save(report)));
        }
        reports.sort(Comparator.comparing(PunctualityReportResponse::routeCode, Comparator.nullsLast(Comparator.naturalOrder())));
        return reports;
    }

    @Override
    @Transactional(readOnly = true)
    public List<PunctualityReportResponse> getPunctualityReports(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw new BusinessException("La fecha final no puede ser anterior a la inicial",
                    HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE");
        }
        return punctualityReportRepository.findByReportDateBetween(from, to).stream()
                .map(MetricsServiceImpl::toResponse)
                .toList();
    }

    private static PunctualityReportResponse toResponse(PunctualityReport report) {
        return new PunctualityReportResponse(report.getReportDate(), report.getRoute().getId(),
                report.getRoute().getCode(), report.getTrips(), report.getOnTimeDeparturePct(),
                report.getOnTimeArrivalPct(), report.getAvgDepartureDelayMin(), report.getAvgArrivalDelayMin(),
                report.getCreatedAt());
    }

    // Viajes que ya salieron agrupados por ruta (en orden de aparición)
    private static Map<Long, List<Trip>> departedTripsByRoute(List<Trip> trips) {
        return trips.stream()
                .filter(t -> t.getDepartedAt() != null && t.getRoute() != null)
                .collect(Collectors.groupingBy(t -> t.getRoute().getId(), LinkedHashMap::new, Collectors.toList()));
    }

    // Puntualidad de un conjunto de viajes: % de salidas/llegadas dentro de la tolerancia y retrasos en minutos
    record Punctuality(int departedTrips, Double onTimeDeparturePct, Double onTimeArrivalPct,
                       Double avgDepartureDelayMin, Double p95DepartureDelayMin,
                       Double avgArrivalDelayMin, Double p95ArrivalDelayMin) {
    }

    static Punctuality punctuality(List<Trip> trips) {
        List<Trip> departed = trips.stream().filter(t -> t.getDepartedAt() != null).toList();
        List<Trip> arrived = trips.stream()
                .filter(t -> t.getArrivedAt() != null && t.getArrivalEta() != null).toList();

        Double onTimeDeparture = departed.isEmpty() ? null : departed.stream()
                .filter(t -> !t.getDepartedAt().isAfter(t.getDepartureTime().plusMinutes(ON_TIME_DEPARTURE_TOLERANCE_MINUTES)))
                .count() * 100.0 / departed.size();
        Double onTimeArrival = arrived.isEmpty() ? null : arrived.stream()
                .filter(t -> !t.getArrivedAt().isAfter(t.getArrivalEta().plusMinutes(ON_TIME_ARRIVAL_TOLERANCE_MINUTES)))
                .count() * 100.0 / arrived.size();

        List<Double> departureDelays = departed.stream()
                .map(t -> delayMinutes(t.getDepartureTime(), t.getDepartedAt())).sorted().toList();
        List<Double> arrivalDelays = arrived.stream()
                .map(t -> delayMinutes(t.getArrivalEta(), t.getArrivedAt())).sorted().toList();

        return new Punctuality(departed.size(), onTimeDeparture, onTimeArrival,
                average(departureDelays), departureDelays.isEmpty() ? null : percentile(departureDelays, 95),
                average(arrivalDelays), arrivalDelays.isEmpty() ? null : percentile(arrivalDelays, 95));
    }

    // Minutos de retraso respecto a lo programado; adelantarse cuenta como 0 (redondeado a 2 decimales)
    static double delayMinutes(LocalDateTime planned, LocalDateTime actual) {
        long seconds = Math.max(0, Duration.between(planned, actual).getSeconds());
        return Math.round(seconds / 60.0 * 100) / 100.0;
    }

    private static Double average(List<Double> values) {
        if (values.isEmpty()) {
            return null;
        }
        double avg = values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        return Math.round(avg * 100) / 100.0;
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
