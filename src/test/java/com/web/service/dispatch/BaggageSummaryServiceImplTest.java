package com.web.service.dispatch;

import com.web.dto.baggage.BaggageResponse;
import com.web.dto.baggage.TripBaggageSummaryResponse;
import com.web.dto.baggage.mapper.BaggageMapper;
import com.web.entity.Baggage;
import com.web.entity.Ticket;
import com.web.exception.ResourceNotFoundException;
import com.web.repository.BaggageRepository;
import com.web.repository.TripRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

// Conteo de equipaje por viaje: solo cuenta el equipaje de tickets vigentes (SOLD)
@ExtendWith(MockitoExtension.class)
class BaggageSummaryServiceImplTest {

    @Mock
    private TripRepository tripRepository;
    @Mock
    private BaggageRepository baggageRepository;
    @Mock
    private BaggageMapper baggageMapper;

    @InjectMocks
    private BaggageSummaryServiceImpl baggageSummaryService;

    private static Baggage baggage(long id, Ticket.TicketStatus status, String weight, String excessFee) {
        Ticket ticket = Ticket.builder().id(id).status(status).build();
        return Baggage.builder()
                .id(id)
                .ticket(ticket)
                .weightKg(new BigDecimal(weight))
                .excessFee(excessFee == null ? null : new BigDecimal(excessFee))
                .tagCode("TAG-" + id)
                .build();
    }

    @Test
    void shouldGetTripBaggage_WithNonExistentTrip_ThrowResourceNotFound() {
        // Given
        when(tripRepository.existsById(99L)).thenReturn(false);

        // When/Then
        assertThatThrownBy(() -> baggageSummaryService.getTripBaggage(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("99");
        verifyNoInteractions(baggageRepository, baggageMapper);
    }

    @Test
    void shouldGetTripBaggage_WithMixedTicketStatuses_CountOnlySoldTickets() {
        // Given
        Baggage sold1 = baggage(1L, Ticket.TicketStatus.SOLD, "20.50", "0");
        Baggage sold2 = baggage(2L, Ticket.TicketStatus.SOLD, "30.00", "35000.00");
        Baggage soldWithoutFee = baggage(3L, Ticket.TicketStatus.SOLD, "5.25", null);
        Baggage cancelled = baggage(4L, Ticket.TicketStatus.CANCELLED, "40.00", "80000.00");
        Baggage noShow = baggage(5L, Ticket.TicketStatus.NO_SHOW, "10.00", "1000.00");
        List<BaggageResponse> mapped = List.of(mock(BaggageResponse.class), mock(BaggageResponse.class),
                mock(BaggageResponse.class));

        when(tripRepository.existsById(1L)).thenReturn(true);
        when(baggageRepository.findByTripId(1L)).thenReturn(List.of(sold1, cancelled, sold2, noShow, soldWithoutFee));
        when(baggageMapper.toResponseList(List.of(sold1, sold2, soldWithoutFee))).thenReturn(mapped);

        // When
        TripBaggageSummaryResponse response = baggageSummaryService.getTripBaggage(1L);

        // Then
        assertThat(response.tripId()).isEqualTo(1L);
        assertThat(response.totalPieces()).isEqualTo(3);
        assertThat(response.totalWeightKg()).isEqualByComparingTo("55.75");
        // El exceso null cuenta como 0
        assertThat(response.totalExcessFee()).isEqualByComparingTo("35000.00");
        assertThat(response.items()).isSameAs(mapped);
    }

    @Test
    void shouldGetTripBaggage_WithoutBaggage_ReturnZeroTotals() {
        // Given
        when(tripRepository.existsById(1L)).thenReturn(true);
        when(baggageRepository.findByTripId(1L)).thenReturn(List.of());
        when(baggageMapper.toResponseList(List.of())).thenReturn(List.of());

        // When
        TripBaggageSummaryResponse response = baggageSummaryService.getTripBaggage(1L);

        // Then
        assertThat(response.totalPieces()).isZero();
        assertThat(response.totalWeightKg()).isEqualByComparingTo("0");
        assertThat(response.totalExcessFee()).isEqualByComparingTo("0");
        assertThat(response.items()).isEmpty();
    }

    @Test
    void shouldGetTripBaggage_WithOnlyCancelledTickets_ReturnZeroTotals() {
        // Given
        when(tripRepository.existsById(1L)).thenReturn(true);
        when(baggageRepository.findByTripId(1L)).thenReturn(List.of(
                baggage(1L, Ticket.TicketStatus.CANCELLED, "12.00", "0")));
        when(baggageMapper.toResponseList(List.of())).thenReturn(List.of());

        // When
        TripBaggageSummaryResponse response = baggageSummaryService.getTripBaggage(1L);

        // Then
        assertThat(response.totalPieces()).isZero();
        assertThat(response.totalWeightKg()).isEqualByComparingTo("0");
    }
}
