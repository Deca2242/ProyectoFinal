package com.web.service.sync;

import com.web.dto.baggage.BaggageCreateRequest;
import com.web.dto.sync.OfflineBoarding;
import com.web.dto.sync.OfflineTicketSale;
import com.web.dto.sync.SyncItemResult;
import com.web.dto.ticket.TicketCreateRequest;
import com.web.dto.ticket.TicketResponse;
import com.web.entity.Ticket;
import com.web.repository.TicketRepository;
import com.web.service.ticket.OfflineSaleContext;
import com.web.service.ticket.TicketService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;

// Procesa cada operación offline en su PROPIA transacción: un conflicto no deshace las demás del lote
@Component
@RequiredArgsConstructor
public class OfflineSyncProcessor {

    private final TicketService ticketService;
    private final TicketRepository ticketRepository;

    // Vende con la lógica normal de compra (bloqueo por viaje, tramo, holds, overbooking y precio).
    // Si el offlineClientId ya se sincronizó, devuelve el ticket existente sin vender otra silla
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SyncItemResult syncSale(OfflineTicketSale sale) {
        Optional<Ticket> existing = ticketRepository.findByOfflineClientId(sale.offlineClientId());
        if (existing.isPresent()) {
            Ticket ticket = existing.get();
            return new SyncItemResult(sale.offlineClientId(), SyncItemResult.Status.DUPLICATE,
                    ticket.getId(), ticket.getQrCode(), null, null);
        }

        BaggageCreateRequest baggage = sale.baggageWeightKg() == null
                ? null : new BaggageCreateRequest(sale.baggageWeightKg(), null);
        // El precio lo calcula el servicio de venta
        TicketCreateRequest request = new TicketCreateRequest(
                sale.tripId(), sale.passengerId(), sale.seatNumber(),
                sale.fromStopId(), null, null, sale.toStopId(), null, null,
                BigDecimal.ZERO, sale.paymentMethod(), baggage, sale.passengerType());

        TicketResponse ticket = ticketService.purchaseTicket(request,
                new OfflineSaleContext(sale.offlineClientId(), sale.soldAt()));
        return new SyncItemResult(sale.offlineClientId(), SyncItemResult.Status.SYNCED,
                ticket.id(), ticket.qrCode(), null, null);
    }

    // Registra el abordaje con la hora del dispositivo; si el ticket ya abordó es un reenvío (DUPLICATE)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SyncItemResult syncBoarding(OfflineBoarding boarding) {
        Optional<Ticket> existing = ticketRepository.findByQrCode(boarding.qrCode());
        if (existing.isPresent() && existing.get().getBoardedAt() != null) {
            return new SyncItemResult(null, SyncItemResult.Status.DUPLICATE,
                    existing.get().getId(), boarding.qrCode(), null, null);
        }

        TicketResponse ticket = ticketService.boardTicket(boarding.qrCode(), boarding.boardedAt());
        return new SyncItemResult(null, SyncItemResult.Status.SYNCED, ticket.id(), boarding.qrCode(), null, null);
    }
}
