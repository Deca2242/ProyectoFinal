package com.web.service.ticket;

import com.web.dto.ticket.TicketCancelResponse;
import com.web.dto.ticket.TicketCreateRequest;
import com.web.dto.ticket.TicketResponse;

import java.util.List;

public interface TicketService {
    
    TicketResponse purchaseTicket(TicketCreateRequest request);
    
    TicketCancelResponse cancelTicket(Long ticketId);
    
    TicketResponse getTicketById(Long id);
    
    List<TicketResponse> getUserTickets(Long userId);
    
    TicketResponse getTicketByQrCode(String qrCode);

    TicketResponse boardTicket(String qrCode);
}

