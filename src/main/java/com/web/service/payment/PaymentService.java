package com.web.service.payment;

import com.web.dto.payment.CashCloseRequest;
import com.web.dto.payment.CashCloseResponse;
import com.web.dto.payment.PaymentConfirmRequest;
import com.web.dto.payment.PaymentResponse;
import com.web.entity.Payment;
import com.web.entity.Ticket;
import com.web.entity.User;

import java.time.LocalDate;
import java.util.List;

public interface PaymentService {

    // Confirma el pago de un ticket PENDING (QR/transferencia/contraentrega) y devuelve su comprobante
    PaymentResponse confirmPayment(PaymentConfirmRequest request, Long confirmedById);

    // Comprobante de una venta cobrada en el acto (taquilla, conductor u offline)
    Payment recordCounterPayment(Ticket ticket, User soldBy);

    // Comprobante de pago de un ticket (404 si aún no está pagado)
    PaymentResponse getReceipt(Long ticketId, Long requesterId);

    CashCloseResponse closeCash(CashCloseRequest request, Long userId);

    // Cierres del usuario (o de todos si es ADMIN), opcionalmente de una fecha
    List<CashCloseResponse> getCashCloses(LocalDate date, Long requesterId);
}
