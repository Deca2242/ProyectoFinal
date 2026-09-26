package com.web.service.payment;

import com.web.dto.payment.CashCloseRequest;
import com.web.dto.payment.CashCloseResponse;
import com.web.dto.payment.PaymentConfirmRequest;
import com.web.dto.ticket.TicketResponse;
import com.web.dto.ticket.mapper.TicketMapper;
import com.web.entity.Ticket;
import com.web.entity.User;
import com.web.exception.BusinessException;
import com.web.exception.ResourceNotFoundException;
import com.web.repository.TicketRepository;
import com.web.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;



@Service
@RequiredArgsConstructor
public class PaymentServiceImpl implements PaymentService {

    private final TicketRepository ticketRepository;
    private final UserRepository userRepository;
    private final TicketMapper ticketMapper;

    //Confirmar el metodo de pago de un ticket
    @Override
    @Transactional
    public TicketResponse confirmPayment(PaymentConfirmRequest request) {
        Ticket ticket = ticketRepository.findById(request.ticketId())
                .orElseThrow(() -> new ResourceNotFoundException("Ticket", request.ticketId()));

        if (ticket.getStatus() != Ticket.TicketStatus.SOLD) {
            throw new BusinessException("Solo se puede confirmar el pago de tickets en estado SOLD",
                    HttpStatus.CONFLICT, "INVALID_TICKET_STATUS");
        }

        ticket.setPaymentMethod(request.paymentMethod());
        Ticket updatedTicket = ticketRepository.save(ticket);



        return ticketMapper.toResponse(updatedTicket);
    }



    // calcula el total esperado de tickets en efectivo y compara con el monto real reportado
    @Override
    @Transactional(readOnly = true)
    public CashCloseResponse closeCash(CashCloseRequest request, Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario", userId));

        // Total esperado = tickets vendidos en efectivo durante el día indicado (según fecha de compra)
        LocalDateTime startOfDay = request.date().atStartOfDay();
        LocalDateTime endOfDay = request.date().plusDays(1).atStartOfDay();
        List<Ticket> cashTickets = ticketRepository.findSoldCashTicketsPurchasedBetween(startOfDay, endOfDay);

        BigDecimal totalCash = cashTickets.stream()
                .map(Ticket::getPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        int ticketCount = cashTickets.size();
        // Diferencia positiva = sobrante en caja, negativa = faltante
        BigDecimal difference = request.actualAmount().subtract(totalCash);

        return new CashCloseResponse(
                userId,
                user.getName(),
                request.date(),
                totalCash,
                request.actualAmount(),
                difference,
                ticketCount,
                LocalDateTime.now()
        );
    }
}
