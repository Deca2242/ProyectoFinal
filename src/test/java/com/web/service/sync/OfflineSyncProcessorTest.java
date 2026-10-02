package com.web.service.sync;

import com.web.dto.sync.OfflineBoarding;
import com.web.dto.sync.OfflineTicketSale;
import com.web.dto.sync.SyncItemResult;
import com.web.dto.ticket.TicketCreateRequest;
import com.web.dto.ticket.TicketResponse;
import com.web.entity.Ticket;
import com.web.exception.ResourceNotFoundException;
import com.web.exception.SeatNotAvailableException;
import com.web.repository.TicketRepository;
import com.web.service.ticket.OfflineSaleContext;
import com.web.service.ticket.TicketService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

// Procesamiento de cada venta o abordaje offline (idempotencia y reutilización del servicio de tickets)
@ExtendWith(MockitoExtension.class)
class OfflineSyncProcessorTest {

    @Mock
    private TicketService ticketService;
    @Mock
    private TicketRepository ticketRepository;

    @InjectMocks
    private OfflineSyncProcessor processor;

    private final LocalDateTime soldAt = LocalDateTime.now().minusHours(1);

    private TicketResponse ticketResponse(long id, String qr) {
        TicketResponse response = mock(TicketResponse.class);
        when(response.id()).thenReturn(id);
        lenient().when(response.qrCode()).thenReturn(qr);
        return response;
    }

    @Test
    void shouldSyncSale_WithNewOfflineId_PurchaseWithContextAndBaggage() {
        // Given
        OfflineTicketSale sale = new OfflineTicketSale("c-1", 1L, 2L, 10, 3L, 4L, Ticket.PaymentMethod.CASH,
                "STUDENT", soldAt, new BigDecimal("30"));
        when(ticketRepository.findByOfflineClientId("c-1")).thenReturn(Optional.empty());
        TicketResponse response = ticketResponse(5L, "QR-5");
        when(ticketService.purchaseTicket(any(TicketCreateRequest.class), any(OfflineSaleContext.class)))
                .thenReturn(response);

        // When
        SyncItemResult result = processor.syncSale(sale);

        // Then
        assertThat(result.status()).isEqualTo(SyncItemResult.Status.SYNCED);
        assertThat(result.ticketId()).isEqualTo(5L);
        assertThat(result.qrCode()).isEqualTo("QR-5");
        assertThat(result.offlineClientId()).isEqualTo("c-1");

        ArgumentCaptor<TicketCreateRequest> request = ArgumentCaptor.forClass(TicketCreateRequest.class);
        ArgumentCaptor<OfflineSaleContext> ctx = ArgumentCaptor.forClass(OfflineSaleContext.class);
        verify(ticketService).purchaseTicket(request.capture(), ctx.capture());
        assertThat(request.getValue().tripId()).isEqualTo(1L);
        assertThat(request.getValue().passengerId()).isEqualTo(2L);
        assertThat(request.getValue().seatNumber()).isEqualTo(10);
        assertThat(request.getValue().fromStopId()).isEqualTo(3L);
        assertThat(request.getValue().toStopId()).isEqualTo(4L);
        assertThat(request.getValue().passengerType()).isEqualTo("STUDENT");
        assertThat(request.getValue().baggage().weightKg()).isEqualByComparingTo("30");
        assertThat(ctx.getValue()).isEqualTo(new OfflineSaleContext("c-1", soldAt));
    }

    @Test
    void shouldSyncSale_WithoutBaggage_SendNullBaggage() {
        // Given
        OfflineTicketSale sale = new OfflineTicketSale("c-1", 1L, 2L, 10, 3L, 4L, Ticket.PaymentMethod.CASH,
                null, soldAt, null);
        when(ticketRepository.findByOfflineClientId("c-1")).thenReturn(Optional.empty());
        TicketResponse response = ticketResponse(5L, "QR-5");
        when(ticketService.purchaseTicket(any(TicketCreateRequest.class), any(OfflineSaleContext.class)))
                .thenReturn(response);

        // When
        processor.syncSale(sale);

        // Then
        verify(ticketService).purchaseTicket(argThat(r -> r.baggage() == null), any(OfflineSaleContext.class));
    }

    @Test
    void shouldSyncSale_WithExistingOfflineId_ReturnDuplicateWithoutSelling() {
        // Given
        OfflineTicketSale sale = new OfflineTicketSale("c-1", 1L, 2L, 10, 3L, 4L, Ticket.PaymentMethod.CASH,
                null, soldAt, null);
        when(ticketRepository.findByOfflineClientId("c-1"))
                .thenReturn(Optional.of(Ticket.builder().id(9L).qrCode("QR-9").build()));

        // When
        SyncItemResult result = processor.syncSale(sale);

        // Then
        assertThat(result.status()).isEqualTo(SyncItemResult.Status.DUPLICATE);
        assertThat(result.ticketId()).isEqualTo(9L);
        assertThat(result.qrCode()).isEqualTo("QR-9");
        verifyNoInteractions(ticketService);
    }

    @Test
    void shouldSyncSale_WithBusinessError_Propagate() {
        // Given
        OfflineTicketSale sale = new OfflineTicketSale("c-1", 1L, 2L, 10, 3L, 4L, Ticket.PaymentMethod.CASH,
                null, soldAt, null);
        when(ticketRepository.findByOfflineClientId("c-1")).thenReturn(Optional.empty());
        when(ticketService.purchaseTicket(any(TicketCreateRequest.class), any(OfflineSaleContext.class)))
                .thenThrow(new SeatNotAvailableException("ocupado"));

        // When/Then
        assertThatThrownBy(() -> processor.syncSale(sale)).isInstanceOf(SeatNotAvailableException.class);
    }

    @Test
    void shouldSyncBoarding_WithNotBoardedTicket_BoardWithDeviceTime() {
        // Given
        LocalDateTime boardedAt = LocalDateTime.now().minusMinutes(15);
        when(ticketRepository.findByQrCode("QR-1")).thenReturn(Optional.of(Ticket.builder().id(1L).build()));
        TicketResponse response = ticketResponse(1L, "QR-1");
        when(ticketService.boardTicket("QR-1", boardedAt)).thenReturn(response);

        // When
        SyncItemResult result = processor.syncBoarding(new OfflineBoarding("QR-1", boardedAt));

        // Then
        assertThat(result.status()).isEqualTo(SyncItemResult.Status.SYNCED);
        assertThat(result.ticketId()).isEqualTo(1L);
        assertThat(result.qrCode()).isEqualTo("QR-1");
    }

    @Test
    void shouldSyncBoarding_WithAlreadyBoardedTicket_ReturnDuplicate() {
        // Given
        when(ticketRepository.findByQrCode("QR-1")).thenReturn(Optional.of(
                Ticket.builder().id(1L).boardedAt(LocalDateTime.now().minusMinutes(30)).build()));

        // When
        SyncItemResult result = processor.syncBoarding(new OfflineBoarding("QR-1", LocalDateTime.now()));

        // Then
        assertThat(result.status()).isEqualTo(SyncItemResult.Status.DUPLICATE);
        assertThat(result.ticketId()).isEqualTo(1L);
        verify(ticketService, never()).boardTicket(anyString(), any());
    }

    @Test
    void shouldSyncBoarding_WithUnknownQr_DelegateToTicketService() {
        // Given: el servicio de tickets responde 404 para QR inexistente
        when(ticketRepository.findByQrCode("NOPE")).thenReturn(Optional.empty());
        when(ticketService.boardTicket(eq("NOPE"), any(LocalDateTime.class)))
                .thenThrow(new ResourceNotFoundException("Ticket", "NOPE"));

        // When/Then
        assertThatThrownBy(() -> processor.syncBoarding(new OfflineBoarding("NOPE", LocalDateTime.now())))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
