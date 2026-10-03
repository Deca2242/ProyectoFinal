package com.web.service.ticket;

import com.web.dto.ticket.TicketCancelResponse;
import com.web.dto.ticket.TicketCreateRequest;
import com.web.dto.ticket.TicketResponse;

import java.time.LocalDateTime;
import java.util.List;

public interface TicketService {

    TicketResponse purchaseTicket(TicketCreateRequest request);

    // Venta offline sincronizada: conserva la hora real de venta y el id del dispositivo (offline null = venta en línea)
    TicketResponse purchaseTicket(TicketCreateRequest request, OfflineSaleContext offline);

    TicketCancelResponse cancelTicket(Long ticketId);
    
    TicketResponse getTicketById(Long id);
    
    List<TicketResponse> getUserTickets(Long userId);
    
    TicketResponse getTicketByQrCode(String qrCode);

    TicketResponse boardTicket(String qrCode);

    // Abordaje offline: conserva la hora del dispositivo si es anterior a ahora (null = ahora)
    TicketResponse boardTicket(String qrCode, LocalDateTime boardedAt);
}

