package com.web.service.admin;

import com.web.dto.admin.MetricsResponse;
import com.web.dto.admin.OccupancyMetrics;
import com.web.dto.admin.OperationalMetrics;
import com.web.dto.admin.ParcelMetrics;
import com.web.dto.admin.PunctualityReportResponse;
import com.web.dto.admin.RevenueMetrics;
import com.web.dto.admin.RoutePunctuality;
import com.web.entity.Baggage;
import com.web.entity.Bus;
import com.web.entity.Parcel;
import com.web.entity.PunctualityReport;
import com.web.entity.Route;
import com.web.entity.Stop;
import com.web.entity.Ticket;
import com.web.entity.Trip;
import com.web.exception.BusinessException;
import com.web.repository.IncidentRepository;
import com.web.repository.ParcelRepository;
import com.web.repository.PunctualityReportRepository;
import com.web.repository.TicketRepository;
import com.web.repository.TripRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.*;

// KPIs del panel de administración: ocupación, ingresos, puntualidad, no-show y encomiendas
@ExtendWith(MockitoExtension.class)
class MetricsServiceImplTest {

    private static final LocalDate START = LocalDate.of(2026, 1, 1);
    private static final LocalDate END = LocalDate.of(2026, 1, 31);
    private static final LocalDateTime DEPARTURE = LocalDateTime.of(2026, 1, 10, 8, 0);
    private static final LocalDateTime ETA = LocalDateTime.of(2026, 1, 10, 12, 0);

    @Mock
    private TripRepository tripRepository;
    @Mock
    private TicketRepository ticketRepository;
    @Mock
    private ParcelRepository parcelRepository;
    @Mock
    private IncidentRepository incidentRepository;
    @Mock
    private PunctualityReportRepository punctualityReportRepository;

    @InjectMocks
    private MetricsServiceImpl metricsService;

    // ---------- Utilidades ----------

    private void givenData(List<Trip> trips, List<Ticket> tickets, List<Parcel> parcels, long incidents) {
        when(tripRepository.findByDateRange(START, END)).thenReturn(trips);
        when(ticketRepository.findByTripDateBetween(START, END)).thenReturn(tickets);
        when(parcelRepository.findByDateRange(START, END)).thenReturn(parcels);
        when(incidentRepository.countByCreatedAtBetween(START.atStartOfDay(), END.plusDays(1).atStartOfDay()))
                .thenReturn(incidents);
    }

    private static Trip trip(long id, int capacity, Trip.TripStatus status) {
        return Trip.builder()
                .id(id)
                .bus(Bus.builder().id(id).capacity(capacity).build())
                .status(status)
                .departureTime(DEPARTURE)
                .arrivalEta(ETA)
                .build();
    }

    private static Ticket ticket(Trip trip, int seat, Ticket.TicketStatus status) {
        return Ticket.builder()
                .trip(trip)
                .seatNumber(seat)
                .status(status)
                .price(BigDecimal.valueOf(100))
                .paymentMethod(Ticket.PaymentMethod.CASH)
                .build();
    }

    private static Ticket paidTicket(Trip trip, Ticket.TicketStatus status, int price,
                                     Ticket.PaymentMethod method, Ticket.SalesChannel channel) {
        Ticket t = ticket(trip, 1, status);
        t.setPrice(BigDecimal.valueOf(price));
        t.setPaymentMethod(method);
        t.setChannel(channel);
        return t;
    }

    private static Parcel parcel(String routeName, String from, String to, Parcel.ParcelStatus status, BigDecimal price) {
        Trip trip = Trip.builder().id(1L).route(Route.builder().id(1L).name(routeName).build()).build();
        return Parcel.builder()
                .trip(trip)
                .fromStop(Stop.builder().name(from).build())
                .toStop(Stop.builder().name(to).build())
                .status(status)
                .price(price)
                .build();
    }

    // ---------- Rango de fechas ----------

    @Test
    void shouldGetMetrics_WithEndBeforeStart_ThrowInvalidDateRange() {
        // When/Then
        assertThatThrownBy(() -> metricsService.getMetrics(END, START))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(be.getCode()).isEqualTo("INVALID_DATE_RANGE");
                });
        verifyNoInteractions(tripRepository, ticketRepository, parcelRepository, incidentRepository);
    }

    @Test
    void shouldGetMetrics_WithSameStartAndEnd_QueryIncidentsForTheWholeDay() {
        // Given
        LocalDate day = LocalDate.of(2026, 3, 5);
        when(tripRepository.findByDateRange(day, day)).thenReturn(List.of());
        when(ticketRepository.findByTripDateBetween(day, day)).thenReturn(List.of());
        when(parcelRepository.findByDateRange(day, day)).thenReturn(List.of());
        when(incidentRepository.countByCreatedAtBetween(day.atStartOfDay(), day.plusDays(1).atStartOfDay()))
                .thenReturn(2L);

        // When
        MetricsResponse response = metricsService.getMetrics(day, day);

        // Then: el fin del rango es exclusivo (inicio del día siguiente)
        assertThat(response.operational().totalIncidents()).isEqualTo(2);
    }

    // ---------- Sin datos ----------

    @Test
    void shouldGetMetrics_WithoutData_ReturnZerosAndNullPunctuality() {
        // Given
        givenData(List.of(), List.of(), List.of(), 0L);

        // When
        MetricsResponse response = metricsService.getMetrics(START, END);

        // Then
        OccupancyMetrics occupancy = response.occupancy();
        assertThat(occupancy.averageOccupancy()).isZero();
        assertThat(occupancy.p50Occupancy()).isZero();
        assertThat(occupancy.p95Occupancy()).isZero();
        assertThat(occupancy.totalTrips()).isZero();
        assertThat(occupancy.totalSeatsSold()).isZero();

        RevenueMetrics revenue = response.revenue();
        assertThat(revenue.totalRevenue()).isEqualByComparingTo("0");
        assertThat(revenue.ticketRevenue()).isEqualByComparingTo("0");
        assertThat(revenue.parcelRevenue()).isEqualByComparingTo("0");
        assertThat(revenue.baggageRevenue()).isEqualByComparingTo("0");
        assertThat(revenue.noShowFeeRevenue()).isEqualByComparingTo("0");
        assertThat(revenue.revenueByPaymentMethod()).isEmpty();
        assertThat(revenue.revenueByChannel()).isEmpty();

        OperationalMetrics operational = response.operational();
        assertThat(operational.onTimeDepartureRate()).isNull();
        assertThat(operational.onTimeArrivalRate()).isNull();
        assertThat(operational.noShowRate()).isZero();
        assertThat(operational.totalCancellations()).isZero();
        assertThat(operational.totalIncidents()).isZero();
        assertThat(operational.totalNoShows()).isZero();

        ParcelMetrics parcels = response.parcels();
        assertThat(parcels.totalParcels()).isZero();
        assertThat(parcels.delivered()).isZero();
        assertThat(parcels.failed()).isZero();
        assertThat(parcels.deliverySuccessRate()).isZero();
        assertThat(parcels.parcelsByRoute()).isEmpty();
        assertThat(parcels.deliveredBySegment()).isEmpty();
        assertThat(parcels.failedBySegment()).isEmpty();
    }

    // ---------- Ocupación ----------

    @Test
    void shouldGetMetrics_WithTrips_CalculateAverageP50AndP95ExcludingCancelledTrips() {
        // Given
        Trip t1 = trip(1L, 10, Trip.TripStatus.DEPARTED);   // 4 sillas distintas -> 40 %
        Trip t2 = trip(2L, 20, Trip.TripStatus.SCHEDULED);  // 10 sillas -> 50 %
        Trip t3 = trip(3L, 10, Trip.TripStatus.ARRIVED);    // sin tickets -> 0 %
        Trip cancelled = trip(4L, 10, Trip.TripStatus.CANCELLED); // se excluye
        Trip noCapacity = trip(5L, 0, Trip.TripStatus.SCHEDULED); // se excluye (sin división por cero)

        List<Ticket> tickets = new ArrayList<>();
        tickets.add(ticket(t1, 1, Ticket.TicketStatus.SOLD));
        tickets.add(ticket(t1, 2, Ticket.TicketStatus.SOLD));
        tickets.add(ticket(t1, 3, Ticket.TicketStatus.SOLD));
        tickets.add(ticket(t1, 3, Ticket.TicketStatus.SOLD));      // misma silla en otro tramo: cuenta una vez
        tickets.add(ticket(t1, 4, Ticket.TicketStatus.NO_SHOW));   // no-show ocupa silla
        tickets.add(ticket(t1, 5, Ticket.TicketStatus.CANCELLED)); // cancelado no ocupa
        for (int seat = 1; seat <= 10; seat++) {
            tickets.add(ticket(t2, seat, Ticket.TicketStatus.SOLD));
        }
        tickets.add(ticket(cancelled, 1, Ticket.TicketStatus.CANCELLED));
        givenData(List.of(t1, t2, t3, cancelled, noCapacity), tickets, List.of(), 0L);

        // When
        OccupancyMetrics occupancy = metricsService.getMetrics(START, END).occupancy();

        // Then: tasas ordenadas [0, 40, 50]
        assertThat(occupancy.totalTrips()).isEqualTo(3);
        assertThat(occupancy.averageOccupancy()).isCloseTo(30.0, within(1e-9));
        assertThat(occupancy.p50Occupancy()).isCloseTo(40.0, within(1e-9));
        assertThat(occupancy.p95Occupancy()).isCloseTo(50.0, within(1e-9));
        // Asientos vendidos = tickets en estado SOLD (4 de t1 + 10 de t2)
        assertThat(occupancy.totalSeatsSold()).isEqualTo(14);
    }

    @Test
    void shouldGetMetrics_WithOnlyCancelledTrips_ReturnZeroOccupancy() {
        // Given
        Trip cancelled = trip(1L, 10, Trip.TripStatus.CANCELLED);
        givenData(List.of(cancelled), List.of(ticket(cancelled, 1, Ticket.TicketStatus.CANCELLED)), List.of(), 0L);

        // When
        OccupancyMetrics occupancy = metricsService.getMetrics(START, END).occupancy();

        // Then
        assertThat(occupancy.totalTrips()).isZero();
        assertThat(occupancy.averageOccupancy()).isZero();
        assertThat(occupancy.p50Occupancy()).isZero();
        assertThat(occupancy.p95Occupancy()).isZero();
        assertThat(occupancy.totalSeatsSold()).isZero();
    }

    // ---------- Ingresos ----------

    @Test
    void shouldGetMetrics_WithTickets_CalculateRevenueByMethodAndChannel() {
        // Given
        Trip t = trip(1L, 40, Trip.TripStatus.DEPARTED);

        Ticket sold = paidTicket(t, Ticket.TicketStatus.SOLD, 100, Ticket.PaymentMethod.CASH, Ticket.SalesChannel.BOX_OFFICE);
        sold.setBaggage(Baggage.builder().excessFee(BigDecimal.valueOf(10)).build());

        // Cancelado con reembolso: aporta lo no reembolsado (200 - 150); su equipaje no cuenta
        Ticket cancelledWithRefund = paidTicket(t, Ticket.TicketStatus.CANCELLED, 200,
                Ticket.PaymentMethod.CARD, Ticket.SalesChannel.APP);
        cancelledWithRefund.setRefundAmount(BigDecimal.valueOf(150));
        cancelledWithRefund.setBaggage(Baggage.builder().excessFee(BigDecimal.valueOf(20)).build());

        // Cancelado sin reembolso registrado y sin canal: aporta todo el precio al canal APP
        Ticket cancelledWithoutRefund = paidTicket(t, Ticket.TicketStatus.CANCELLED, 80,
                Ticket.PaymentMethod.CASH, null);

        // No-show: aporta su precio más el fee; equipaje con excessFee null no suma
        Ticket noShow = paidTicket(t, Ticket.TicketStatus.NO_SHOW, 60, Ticket.PaymentMethod.QR, Ticket.SalesChannel.APP);
        noShow.setNoShowFee(BigDecimal.valueOf(15));
        noShow.setBaggage(Baggage.builder().excessFee(null).build());

        Ticket soldWithoutBaggage = paidTicket(t, Ticket.TicketStatus.SOLD, 40,
                Ticket.PaymentMethod.TRANSFER, Ticket.SalesChannel.BOX_OFFICE);

        List<Parcel> parcels = List.of(
                parcel("R1", "A", "B", Parcel.ParcelStatus.CREATED, BigDecimal.valueOf(30)),
                parcel("R1", "A", "B", Parcel.ParcelStatus.CREATED, null),
                parcel("R1", "A", "B", Parcel.ParcelStatus.DELIVERED, BigDecimal.valueOf(20)));

        givenData(List.of(t),
                List.of(sold, cancelledWithRefund, cancelledWithoutRefund, noShow, soldWithoutBaggage),
                parcels, 0L);

        // When
        RevenueMetrics revenue = metricsService.getMetrics(START, END).revenue();

        // Then
        assertThat(revenue.ticketRevenue()).isEqualByComparingTo("330");   // 100 + 50 + 80 + 60 + 40
        assertThat(revenue.baggageRevenue()).isEqualByComparingTo("10");
        assertThat(revenue.noShowFeeRevenue()).isEqualByComparingTo("15");
        assertThat(revenue.parcelRevenue()).isEqualByComparingTo("50");
        assertThat(revenue.totalRevenue()).isEqualByComparingTo("405");

        assertThat(revenue.revenueByPaymentMethod()).containsOnlyKeys("CASH", "CARD", "QR", "TRANSFER");
        assertThat(revenue.revenueByPaymentMethod().get("CASH")).isEqualByComparingTo("180");
        assertThat(revenue.revenueByPaymentMethod().get("CARD")).isEqualByComparingTo("50");
        assertThat(revenue.revenueByPaymentMethod().get("QR")).isEqualByComparingTo("60");
        assertThat(revenue.revenueByPaymentMethod().get("TRANSFER")).isEqualByComparingTo("40");

        assertThat(revenue.revenueByChannel()).containsOnlyKeys("APP", "BOX_OFFICE");
        assertThat(revenue.revenueByChannel().get("APP")).isEqualByComparingTo("190");
        assertThat(revenue.revenueByChannel().get("BOX_OFFICE")).isEqualByComparingTo("140");
    }

    @Test
    void shouldGetMetrics_WithFullyRefundedTicket_ContributeZeroRevenue() {
        // Given: cancelación de la empresa (reembolso total)
        Trip t = trip(1L, 40, Trip.TripStatus.CANCELLED);
        Ticket refunded = paidTicket(t, Ticket.TicketStatus.CANCELLED, 100,
                Ticket.PaymentMethod.CARD, Ticket.SalesChannel.APP);
        refunded.setRefundAmount(BigDecimal.valueOf(100));
        givenData(List.of(t), List.of(refunded), List.of(), 0L);

        // When
        RevenueMetrics revenue = metricsService.getMetrics(START, END).revenue();

        // Then
        assertThat(revenue.totalRevenue()).isEqualByComparingTo("0");
        assertThat(revenue.revenueByPaymentMethod().get("CARD")).isEqualByComparingTo("0");
    }

    @Test
    void shouldGetMetrics_WithRefundOnNonCancelledTicket_IgnoreRefund() {
        // Given: el reembolso solo se descuenta si el ticket está cancelado
        Trip t = trip(1L, 40, Trip.TripStatus.SCHEDULED);
        Ticket sold = paidTicket(t, Ticket.TicketStatus.SOLD, 100, Ticket.PaymentMethod.CASH, Ticket.SalesChannel.APP);
        sold.setRefundAmount(BigDecimal.valueOf(70));
        givenData(List.of(t), List.of(sold), List.of(), 0L);

        // When
        RevenueMetrics revenue = metricsService.getMetrics(START, END).revenue();

        // Then
        assertThat(revenue.ticketRevenue()).isEqualByComparingTo("100");
    }

    // ---------- Puntualidad, no-show, cancelaciones e incidentes ----------

    @Test
    void shouldGetMetrics_WithDepartedAndArrivedTrips_ApplyFiveAndTenMinuteTolerances() {
        // Given
        Trip onTime = trip(1L, 40, Trip.TripStatus.ARRIVED);
        onTime.setDepartedAt(DEPARTURE.plusMinutes(5));  // en el límite de la tolerancia de salida
        onTime.setArrivedAt(ETA.plusMinutes(10));        // en el límite de la tolerancia de llegada

        Trip late = trip(2L, 40, Trip.TripStatus.ARRIVED);
        late.setDepartedAt(DEPARTURE.plusMinutes(5).plusSeconds(1));
        late.setArrivedAt(ETA.plusMinutes(10).plusSeconds(1));

        Trip early = trip(3L, 40, Trip.TripStatus.ARRIVED);
        early.setDepartedAt(DEPARTURE.minusMinutes(2));
        early.setArrivedAt(ETA.minusMinutes(30));
        early.setArrivalEta(null); // sin ETA no cuenta para la puntualidad de llegada

        Trip notDeparted = trip(4L, 40, Trip.TripStatus.SCHEDULED);

        givenData(List.of(onTime, late, early, notDeparted), List.of(), List.of(), 0L);

        // When
        OperationalMetrics operational = metricsService.getMetrics(START, END).operational();

        // Then: salidas 2 de 3 puntuales; llegadas 1 de 2
        assertThat(operational.onTimeDepartureRate()).isCloseTo(200.0 / 3, within(1e-9));
        assertThat(operational.onTimeArrivalRate()).isCloseTo(50.0, within(1e-9));
    }

    @Test
    void shouldGetMetrics_WithDepartedTripWithoutArrival_ReturnNullArrivalRate() {
        // Given
        Trip departed = trip(1L, 40, Trip.TripStatus.DEPARTED);
        departed.setDepartedAt(DEPARTURE.plusMinutes(1));
        givenData(List.of(departed), List.of(), List.of(), 0L);

        // When
        OperationalMetrics operational = metricsService.getMetrics(START, END).operational();

        // Then
        assertThat(operational.onTimeDepartureRate()).isEqualTo(100.0);
        assertThat(operational.onTimeArrivalRate()).isNull();
    }

    @Test
    void shouldGetMetrics_WithTickets_CalculateNoShowRateCancellationsAndIncidents() {
        // Given: 3 vendidos, 1 no-show, 2 cancelados
        Trip t = trip(1L, 40, Trip.TripStatus.DEPARTED);
        List<Ticket> tickets = List.of(
                ticket(t, 1, Ticket.TicketStatus.SOLD),
                ticket(t, 2, Ticket.TicketStatus.SOLD),
                ticket(t, 3, Ticket.TicketStatus.SOLD),
                ticket(t, 4, Ticket.TicketStatus.NO_SHOW),
                ticket(t, 5, Ticket.TicketStatus.CANCELLED),
                ticket(t, 6, Ticket.TicketStatus.CANCELLED));
        givenData(List.of(t), tickets, List.of(), 7L);

        // When
        OperationalMetrics operational = metricsService.getMetrics(START, END).operational();

        // Then: la tasa de no-show excluye los cancelados
        assertThat(operational.noShowRate()).isCloseTo(25.0, within(1e-9));
        assertThat(operational.totalNoShows()).isEqualTo(1);
        assertThat(operational.totalCancellations()).isEqualTo(2);
        assertThat(operational.totalIncidents()).isEqualTo(7);
    }

    @Test
    void shouldGetMetrics_WithOnlyCancelledTickets_ReturnZeroNoShowRate() {
        // Given
        Trip t = trip(1L, 40, Trip.TripStatus.SCHEDULED);
        givenData(List.of(t), List.of(ticket(t, 1, Ticket.TicketStatus.CANCELLED)), List.of(), 0L);

        // When
        OperationalMetrics operational = metricsService.getMetrics(START, END).operational();

        // Then
        assertThat(operational.noShowRate()).isZero();
        assertThat(operational.totalCancellations()).isEqualTo(1);
    }

    // ---------- Encomiendas ----------

    @Test
    void shouldGetMetrics_WithParcels_GroupByRouteAndSegment() {
        // Given
        List<Parcel> parcels = List.of(
                parcel("R1", "A", "B", Parcel.ParcelStatus.DELIVERED, BigDecimal.TEN),
                parcel("R1", "A", "B", Parcel.ParcelStatus.FAILED, BigDecimal.TEN),
                parcel("R2", "B", "C", Parcel.ParcelStatus.DELIVERED, BigDecimal.TEN),
                parcel("R2", "A", "B", Parcel.ParcelStatus.CREATED, BigDecimal.TEN),
                parcel("R1", "A", "C", Parcel.ParcelStatus.IN_TRANSIT, BigDecimal.TEN));
        givenData(List.of(), List.of(), parcels, 0L);

        // When
        ParcelMetrics metrics = metricsService.getMetrics(START, END).parcels();

        // Then
        assertThat(metrics.totalParcels()).isEqualTo(5);
        assertThat(metrics.delivered()).isEqualTo(2);
        assertThat(metrics.failed()).isEqualTo(1);
        assertThat(metrics.deliverySuccessRate()).isCloseTo(200.0 / 3, within(1e-9));
        assertThat(metrics.parcelsByRoute()).containsExactlyInAnyOrderEntriesOf(
                java.util.Map.of("R1", 3, "R2", 2));
        assertThat(metrics.deliveredBySegment()).containsExactlyInAnyOrderEntriesOf(
                java.util.Map.of("A → B", 1, "B → C", 1));
        assertThat(metrics.failedBySegment()).containsExactlyInAnyOrderEntriesOf(
                java.util.Map.of("A → B", 1));
    }

    @Test
    void shouldGetMetrics_WithParcelsNotFinished_ReturnZeroSuccessRate() {
        // Given: ninguna entregada ni fallida
        givenData(List.of(), List.of(),
                List.of(parcel("R1", "A", "B", Parcel.ParcelStatus.IN_TRANSIT, BigDecimal.TEN)), 0L);

        // When
        ParcelMetrics metrics = metricsService.getMetrics(START, END).parcels();

        // Then
        assertThat(metrics.totalParcels()).isEqualTo(1);
        assertThat(metrics.deliverySuccessRate()).isZero();
        assertThat(metrics.deliveredBySegment()).isEmpty();
        assertThat(metrics.failedBySegment()).isEmpty();
    }

    // ---------- percentile ----------

    @Test
    void shouldCalculatePercentile_WithEmptyList_ReturnZero() {
        // When/Then
        assertThat(MetricsServiceImpl.percentile(List.of(), 50)).isZero();
        assertThat(MetricsServiceImpl.percentile(List.of(), 95)).isZero();
    }

    @Test
    void shouldCalculatePercentile_WithSingleValue_ReturnThatValue() {
        // When/Then
        assertThat(MetricsServiceImpl.percentile(List.of(42.0), 50)).isEqualTo(42.0);
        assertThat(MetricsServiceImpl.percentile(List.of(42.0), 95)).isEqualTo(42.0);
    }

    @Test
    void shouldCalculatePercentile_WithNearestRankMethod() {
        // Given
        List<Double> sorted = List.of(10.0, 20.0, 30.0, 40.0);

        // When/Then: rango = ceil(p/100 * n)
        assertThat(MetricsServiceImpl.percentile(sorted, 50)).isEqualTo(20.0);  // ceil(2.0) = 2
        assertThat(MetricsServiceImpl.percentile(sorted, 51)).isEqualTo(30.0);  // ceil(2.04) = 3
        assertThat(MetricsServiceImpl.percentile(sorted, 95)).isEqualTo(40.0);  // ceil(3.8) = 4
        assertThat(MetricsServiceImpl.percentile(sorted, 100)).isEqualTo(40.0);
        assertThat(MetricsServiceImpl.percentile(sorted, 0)).isEqualTo(10.0);   // rango 0 -> primer elemento
    }

    // ---------- Retrasos y puntualidad por ruta ----------

    private static Trip routeTrip(long id, Route route, int departureDelayMin, Integer arrivalDelayMin) {
        Trip trip = trip(id, 40, Trip.TripStatus.ARRIVED);
        trip.setRoute(route);
        trip.setDepartedAt(DEPARTURE.plusMinutes(departureDelayMin));
        if (arrivalDelayMin != null) {
            trip.setArrivedAt(ETA.plusMinutes(arrivalDelayMin));
        }
        return trip;
    }

    @Test
    void shouldGetMetrics_WithDepartedTrips_CalculateAverageAndP95DelaysAndPunctualityByRoute() {
        // Given: ruta B (2 viajes) y ruta A (1 viaje); una salida adelantada cuenta como 0 min de retraso
        Route routeA = Route.builder().id(1L).code("A-01").build();
        Route routeB = Route.builder().id(2L).code("B-02").build();
        Trip b1 = routeTrip(1L, routeB, 0, 4);
        Trip b2 = routeTrip(2L, routeB, 20, null);           // aún no llega
        Trip a1 = routeTrip(3L, routeA, -3, 12);              // salió 3 min antes
        Trip notDeparted = trip(4L, 40, Trip.TripStatus.SCHEDULED);
        notDeparted.setRoute(routeA);
        givenData(List.of(b1, b2, a1, notDeparted), List.of(), List.of(), 0L);

        // When
        OperationalMetrics operational = metricsService.getMetrics(START, END).operational();

        // Then: retrasos de salida [0, 0, 20] y de llegada [4, 12]
        assertThat(operational.avgDepartureDelayMin()).isCloseTo(6.67, within(1e-9));
        assertThat(operational.p95DepartureDelayMin()).isEqualTo(20.0);
        assertThat(operational.avgArrivalDelayMin()).isEqualTo(8.0);
        assertThat(operational.p95ArrivalDelayMin()).isEqualTo(12.0);

        // Desglose ordenado por código de ruta y solo con viajes que salieron
        assertThat(operational.punctualityByRoute()).hasSize(2);
        RoutePunctuality a = operational.punctualityByRoute().get(0);
        assertThat(a.routeId()).isEqualTo(1L);
        assertThat(a.routeCode()).isEqualTo("A-01");
        assertThat(a.trips()).isEqualTo(1);
        assertThat(a.onTimeDeparturePct()).isEqualTo(100.0);
        assertThat(a.onTimeArrivalPct()).isZero();             // 12 min > tolerancia de 10
        assertThat(a.avgDepartureDelayMin()).isZero();
        RoutePunctuality b = operational.punctualityByRoute().get(1);
        assertThat(b.routeCode()).isEqualTo("B-02");
        assertThat(b.trips()).isEqualTo(2);
        assertThat(b.onTimeDeparturePct()).isEqualTo(50.0);
        assertThat(b.onTimeArrivalPct()).isEqualTo(100.0);
        assertThat(b.avgDepartureDelayMin()).isEqualTo(10.0);
    }

    @Test
    void shouldGetMetrics_WithoutDepartedTrips_ReturnNullDelaysAndEmptyBreakdown() {
        // Given
        givenData(List.of(trip(1L, 40, Trip.TripStatus.SCHEDULED)), List.of(), List.of(), 0L);

        // When
        OperationalMetrics operational = metricsService.getMetrics(START, END).operational();

        // Then
        assertThat(operational.avgDepartureDelayMin()).isNull();
        assertThat(operational.p95DepartureDelayMin()).isNull();
        assertThat(operational.avgArrivalDelayMin()).isNull();
        assertThat(operational.p95ArrivalDelayMin()).isNull();
        assertThat(operational.punctualityByRoute()).isEmpty();
    }

    @Test
    void shouldCalculateDelayMinutes_WithSecondsAndEarlyDepartures() {
        // When/Then
        assertThat(MetricsServiceImpl.delayMinutes(DEPARTURE, DEPARTURE.plusSeconds(90))).isEqualTo(1.5);
        assertThat(MetricsServiceImpl.delayMinutes(DEPARTURE, DEPARTURE.minusMinutes(5))).isZero();
        assertThat(MetricsServiceImpl.delayMinutes(DEPARTURE, DEPARTURE.plusSeconds(20))).isEqualTo(0.33);
    }

    // ---------- Reporte diario de puntualidad ----------

    @Test
    void shouldGeneratePunctualityReport_CreateOneRowPerRouteAndUpdateExistingOnes() {
        // Given: la ruta A ya tenía reporte ese día (se regenera), la B no
        LocalDate day = LocalDate.of(2026, 1, 10);
        Route routeA = Route.builder().id(1L).code("A-01").build();
        Route routeB = Route.builder().id(2L).code("B-02").build();
        Trip a1 = routeTrip(1L, routeA, 2, 5);
        Trip a2 = routeTrip(2L, routeA, 10, 20);
        Trip b1 = routeTrip(3L, routeB, 0, null);
        PunctualityReport existingA = PunctualityReport.builder().id(7L).reportDate(day).route(routeA).trips(1)
                .createdAt(LocalDateTime.of(2026, 1, 11, 0, 5)).build();
        when(tripRepository.findByDateRange(day, day)).thenReturn(List.of(b1, a1, a2));
        when(punctualityReportRepository.findByReportDateAndRouteId(day, 1L)).thenReturn(Optional.of(existingA));
        when(punctualityReportRepository.findByReportDateAndRouteId(day, 2L)).thenReturn(Optional.empty());
        when(punctualityReportRepository.save(any(PunctualityReport.class))).thenAnswer(inv -> inv.getArgument(0));

        // When
        List<PunctualityReportResponse> reports = metricsService.generatePunctualityReport(day);

        // Then
        ArgumentCaptor<PunctualityReport> captor = ArgumentCaptor.forClass(PunctualityReport.class);
        verify(punctualityReportRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).contains(existingA);
        assertThat(existingA.getTrips()).isEqualTo(2);
        assertThat(existingA.getOnTimeDeparturePct()).isEqualTo(50.0);
        assertThat(existingA.getOnTimeArrivalPct()).isEqualTo(50.0);
        assertThat(existingA.getAvgDepartureDelayMin()).isEqualTo(6.0);
        assertThat(existingA.getAvgArrivalDelayMin()).isEqualTo(12.5);

        PunctualityReport newB = captor.getAllValues().stream().filter(r -> r != existingA).findFirst().orElseThrow();
        assertThat(newB.getId()).isNull();
        assertThat(newB.getReportDate()).isEqualTo(day);
        assertThat(newB.getRoute()).isSameAs(routeB);
        assertThat(newB.getTrips()).isEqualTo(1);
        assertThat(newB.getOnTimeArrivalPct()).isNull();
        assertThat(newB.getAvgArrivalDelayMin()).isNull();

        assertThat(reports).extracting(PunctualityReportResponse::routeCode).containsExactly("A-01", "B-02");
    }

    @Test
    void shouldGeneratePunctualityReport_WithoutDepartedTrips_SaveNothing() {
        // Given
        LocalDate day = LocalDate.of(2026, 1, 10);
        when(tripRepository.findByDateRange(day, day)).thenReturn(List.of(trip(1L, 40, Trip.TripStatus.CANCELLED)));

        // When
        List<PunctualityReportResponse> reports = metricsService.generatePunctualityReport(day);

        // Then
        assertThat(reports).isEmpty();
        verifyNoInteractions(punctualityReportRepository);
    }

    @Test
    void shouldGetPunctualityReports_MapStoredRows() {
        // Given
        Route route = Route.builder().id(3L).code("BOG-TUN").build();
        PunctualityReport stored = PunctualityReport.builder().reportDate(START).route(route).trips(4)
                .onTimeDeparturePct(75.0).onTimeArrivalPct(50.0).avgDepartureDelayMin(6.25).avgArrivalDelayMin(12.0)
                .createdAt(START.plusDays(1).atTime(0, 5)).build();
        when(punctualityReportRepository.findByReportDateBetween(START, END)).thenReturn(List.of(stored));

        // When
        List<PunctualityReportResponse> result = metricsService.getPunctualityReports(START, END);

        // Then
        assertThat(result).containsExactly(new PunctualityReportResponse(START, 3L, "BOG-TUN", 4, 75.0, 50.0,
                6.25, 12.0, START.plusDays(1).atTime(0, 5)));
    }

    @Test
    void shouldGetPunctualityReports_WithEndBeforeStart_ThrowInvalidDateRange() {
        // When/Then
        assertThatThrownBy(() -> metricsService.getPunctualityReports(END, START))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("INVALID_DATE_RANGE");
        verifyNoInteractions(punctualityReportRepository);
    }

    @Test
    void shouldCalculatePercentile_WithTwentyValues_ReturnNineteenthForP95() {
        // Given: 1..20
        List<Double> sorted = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            sorted.add((double) i);
        }

        // When/Then
        assertThat(MetricsServiceImpl.percentile(sorted, 95)).isEqualTo(19.0);
        assertThat(MetricsServiceImpl.percentile(sorted, 50)).isEqualTo(10.0);
    }
}
