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



    // Calcula el efectivo esperado del día y lo compara con el monto real reportado:
    //  + tickets en efectivo vendidos ese día (en cualquier estado: el dinero se recibió al vender)
    //  + cargos por exceso de equipaje de esos tickets
    //  - reembolsos de tickets en efectivo cancelados ese día
    @Override
    @Transactional(readOnly = true)
    public CashCloseResponse closeCash(CashCloseRequest request, Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario", userId));

        LocalDateTime startOfDay = request.date().atStartOfDay();
        LocalDateTime endOfDay = request.date().plusDays(1).atStartOfDay();
        List<Ticket> soldTickets = ticketRepository.findCashTicketsPurchasedBetween(startOfDay, endOfDay);
        List<Ticket> cancelledTickets = ticketRepository.findCashTicketsCancelledBetween(startOfDay, endOfDay);

        BigDecimal sales = soldTickets.stream()
                .map(t -> t.getPrice().add(excessFee(t)))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal refunds = cancelledTickets.stream()
                .map(t -> t.getRefundAmount() == null ? BigDecimal.ZERO : t.getRefundAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal expectedCash = sales.subtract(refunds);

        // Diferencia positiva = sobrante en caja, negativa = faltante
        BigDecimal difference = request.actualAmount().subtract(expectedCash);

        return new CashCloseResponse(
                userId,
                user.getName(),
                request.date(),
                expectedCash,
                request.actualAmount(),
                difference,
                soldTickets.size(),
                LocalDateTime.now()
        );
    }

    private BigDecimal excessFee(Ticket ticket) {
        if (ticket.getBaggage() == null || ticket.getBaggage().getExcessFee() == null) {
            return BigDecimal.ZERO;
        }
        return ticket.getBaggage().getExcessFee();
    }
}
