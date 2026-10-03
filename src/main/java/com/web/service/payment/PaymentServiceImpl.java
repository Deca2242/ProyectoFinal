package com.web.service.payment;

import com.web.dto.payment.CashCloseRequest;
import com.web.dto.payment.CashCloseResponse;
import com.web.dto.payment.PaymentConfirmRequest;
import com.web.dto.payment.PaymentResponse;
import com.web.dto.payment.mapper.PaymentMapper;
import com.web.entity.CashClose;
import com.web.entity.Payment;
import com.web.entity.Ticket;
import com.web.entity.User;
import com.web.exception.BusinessException;
import com.web.exception.InvalidStateTransitionException;
import com.web.exception.ResourceNotFoundException;
import com.web.repository.AssignmentRepository;
import com.web.repository.CashCloseRepository;
import com.web.repository.PaymentRepository;
import com.web.repository.TicketRepository;
import com.web.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


@Service
@RequiredArgsConstructor
public class PaymentServiceImpl implements PaymentService {

    private final TicketRepository ticketRepository;
    private final UserRepository userRepository;
    private final PaymentRepository paymentRepository;
    private final CashCloseRepository cashCloseRepository;
    private final AssignmentRepository assignmentRepository;
    private final PaymentMapper paymentMapper;

    // Confirma el pago de un ticket pendiente: la taquilla verifica el QR/transferencia o cobra en efectivo,
    // o el conductor asignado cobra al subir. El ticket pasa a PAID y queda el comprobante digital
    @Override
    @Transactional
    public PaymentResponse confirmPayment(PaymentConfirmRequest request, Long confirmedById) {
        User confirmer = userRepository.findById(confirmedById)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario", confirmedById));
        Ticket ticket = ticketRepository.findById(request.ticketId())
                .orElseThrow(() -> new ResourceNotFoundException("Ticket", request.ticketId()));

        // Un conductor solo cobra los tickets de los viajes que tiene asignados
        if (confirmer.getRole() == User.Role.DRIVER) {
            requireAssignedDriver(ticket, confirmer);
        }

        if (ticket.getStatus() != Ticket.TicketStatus.SOLD) {
            throw new BusinessException("Solo se puede confirmar el pago de tickets en estado SOLD",
                    HttpStatus.CONFLICT, "INVALID_TICKET_STATUS");
        }

        // Un ticket ya pagado no se vuelve a confirmar ni cambia de método de pago
        if (ticket.getPaymentStatus() == Ticket.PaymentStatus.PAID) {
            throw new BusinessException("El pago del ticket ya fue confirmado",
                    HttpStatus.CONFLICT, "PAYMENT_ALREADY_CONFIRMED");
        }

        String reference = request.transactionReference() == null || request.transactionReference().isBlank()
                ? null : request.transactionReference().trim();
        if (request.paymentMethod() != Ticket.PaymentMethod.CASH && reference == null) {
            throw new BusinessException("La referencia de la transacción es obligatoria para pagos "
                    + request.paymentMethod(), HttpStatus.BAD_REQUEST, "TRANSACTION_REFERENCE_REQUIRED");
        }

        if (request.amount() != null && request.amount().compareTo(ticket.getPrice()) != 0) {
            throw new BusinessException("El monto pagado (" + request.amount() + ") no coincide con el precio del ticket ("
                    + ticket.getPrice() + ")", HttpStatus.BAD_REQUEST, "AMOUNT_MISMATCH");
        }

        // El pago entra en la caja de hoy de quien lo recibe: si ya la cerró, no se admite
        LocalDateTime now = LocalDateTime.now();
        if (cashCloseRepository.existsByUserIdAndCloseDate(confirmer.getId(), now.toLocalDate())) {
            throw new InvalidStateTransitionException("La caja del " + now.toLocalDate()
                    + " ya está cerrada: no se pueden registrar más pagos ese día");
        }

        ticket.setPaymentMethod(request.paymentMethod());
        ticket.setPaymentStatus(Ticket.PaymentStatus.PAID);
        ticket.setPaidAt(now);
        ticketRepository.save(ticket);

        Payment payment = paymentRepository.save(Payment.builder()
                .ticket(ticket)
                .method(request.paymentMethod())
                .amount(ticket.getPrice())
                .transactionReference(reference)
                .proofImageUrl(request.proofImageUrl())
                .paidAt(now)
                .confirmedBy(confirmer)
                .build());

        return paymentMapper.toResponse(payment);
    }

    // Comprobante de una venta cobrada en el acto: el dinero lo recibe quien vende
    @Override
    @Transactional
    public Payment recordCounterPayment(Ticket ticket, User soldBy) {
        return paymentRepository.save(Payment.builder()
                .ticket(ticket)
                .method(ticket.getPaymentMethod())
                .amount(ticket.getPrice())
                .paidAt(ticket.getPaidAt() != null ? ticket.getPaidAt() : LocalDateTime.now())
                .confirmedBy(soldBy)
                .build());
    }

    // Comprobante de pago: el pasajero solo ve los de sus tickets; el personal ve cualquiera
    @Override
    @Transactional(readOnly = true)
    public PaymentResponse getReceipt(Long ticketId, Long requesterId) {
        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket", ticketId));
        User requester = userRepository.findById(requesterId)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario", requesterId));

        if (requester.getRole() == User.Role.PASSENGER
                && (ticket.getPassenger() == null || !requester.getId().equals(ticket.getPassenger().getId()))) {
            throw new BusinessException("Un pasajero solo puede operar sobre sus propios tickets",
                    HttpStatus.FORBIDDEN, "NOT_TICKET_OWNER");
        }

        if (ticket.getPaymentStatus() != Ticket.PaymentStatus.PAID) {
            throw new ResourceNotFoundException("El ticket " + ticketId + " no tiene comprobante: el pago está pendiente");
        }

        Payment payment = paymentRepository.findByTicketId(ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("Comprobante de pago del ticket", ticketId));
        return paymentMapper.toResponse(payment);
    }

    // Cierra y guarda la caja del día de quien cierra (CLERK/DRIVER). Solo cuentan los tickets pagados,
    // por la fecha de su pago (paidAt) y no de su compra: una venta offline de ayer sincronizada hoy se
    // cobra al sincronizar y entra en el cierre de hoy, así no se pierde aunque la caja de ayer ya esté cerrada.
    //  efectivo esperado = pagos en efectivo que recibió ese día (en cualquier estado del ticket)
    //                    + exceso de equipaje de esos tickets
    //                    - reembolsos de tickets pagados en efectivo cancelados ese día
    @Override
    @Transactional
    public CashCloseResponse closeCash(CashCloseRequest request, Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario", userId));

        LocalDate date = request.date();
        if (cashCloseRepository.existsByUserIdAndCloseDate(userId, date)) {
            throw new BusinessException("La caja del " + date + " ya fue cerrada", HttpStatus.CONFLICT, "CASH_ALREADY_CLOSED");
        }

        LocalDateTime startOfDay = date.atStartOfDay();
        LocalDateTime endOfDay = date.plusDays(1).atStartOfDay();
        List<Payment> received = paymentRepository.findReceivedByUserBetween(userId, startOfDay, endOfDay);
        List<Payment> refunded = paymentRepository.findCashRefundedByUserBetween(userId, startOfDay, endOfDay);

        Map<String, BigDecimal> totalsByMethod = new LinkedHashMap<>();
        for (Ticket.PaymentMethod method : Ticket.PaymentMethod.values()) {
            totalsByMethod.put(method.name(), BigDecimal.ZERO);
        }
        for (Payment payment : received) {
            totalsByMethod.merge(payment.getMethod().name(), payment.getAmount(), BigDecimal::add);
        }

        BigDecimal baggage = received.stream()
                .filter(p -> p.getMethod() == Ticket.PaymentMethod.CASH)
                .map(p -> excessFee(p.getTicket()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal refunds = refunded.stream()
                .map(p -> p.getTicket().getRefundAmount() == null ? BigDecimal.ZERO : p.getTicket().getRefundAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal expectedCash = totalsByMethod.get(Ticket.PaymentMethod.CASH.name()).add(baggage).subtract(refunds);

        // Diferencia positiva = sobrante en caja, negativa = faltante
        BigDecimal difference = request.actualAmount().subtract(expectedCash);

        CashClose cashClose = cashCloseRepository.save(CashClose.builder()
                .user(user)
                .closeDate(date)
                .expectedAmount(expectedCash)
                .actualAmount(request.actualAmount())
                .difference(difference)
                .totalsByMethod(totalsByMethod)
                .ticketsCount(received.size())
                .refundsTotal(refunds)
                .baggageTotal(baggage)
                .notes(request.notes())
                .closedAt(LocalDateTime.now())
                .build());

        return paymentMapper.toCashCloseResponse(cashClose);
    }

    // CLERK/DRIVER ven sus propios cierres; ADMIN los de todos
    @Override
    @Transactional(readOnly = true)
    public List<CashCloseResponse> getCashCloses(LocalDate date, Long requesterId) {
        User requester = userRepository.findById(requesterId)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario", requesterId));

        List<CashClose> closes;
        if (requester.getRole() == User.Role.ADMIN) {
            closes = date != null
                    ? cashCloseRepository.findByCloseDateOrderByClosedAtAsc(date)
                    : cashCloseRepository.findAllByOrderByCloseDateDescClosedAtDesc();
        } else {
            closes = date != null
                    ? cashCloseRepository.findByUserIdAndCloseDate(requesterId, date)
                    : cashCloseRepository.findByUserIdOrderByCloseDateDesc(requesterId);
        }
        return paymentMapper.toCashCloseResponseList(closes);
    }

    private void requireAssignedDriver(Ticket ticket, User driver) {
        boolean assigned = assignmentRepository.findByTripId(ticket.getTrip().getId())
                .map(a -> a.getDriver() != null && driver.getId().equals(a.getDriver().getId()))
                .orElse(false);
        if (!assigned) {
            throw new BusinessException("El conductor no está asignado a este viaje",
                    HttpStatus.FORBIDDEN, "DRIVER_NOT_ASSIGNED");
        }
    }

    private BigDecimal excessFee(Ticket ticket) {
        if (ticket.getBaggage() == null || ticket.getBaggage().getExcessFee() == null) {
            return BigDecimal.ZERO;
        }
        return ticket.getBaggage().getExcessFee();
    }
}
