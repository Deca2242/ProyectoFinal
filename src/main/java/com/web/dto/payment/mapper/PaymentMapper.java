package com.web.dto.payment.mapper;

import com.web.dto.payment.CashCloseResponse;
import com.web.dto.payment.PaymentResponse;
import com.web.entity.CashClose;
import com.web.entity.Payment;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper(componentModel = "spring")
public interface PaymentMapper {

    // Comprobante digital a partir del pago y su ticket
    @Mapping(target = "paymentId", source = "id")
    @Mapping(target = "receiptNumber", expression = "java(receiptNumber(payment))")
    @Mapping(target = "ticketId", source = "ticket.id")
    @Mapping(target = "qrCode", source = "ticket.qrCode")
    @Mapping(target = "paymentMethod", source = "method")
    @Mapping(target = "paymentStatus", source = "ticket.paymentStatus")
    @Mapping(target = "confirmedBy", source = "confirmedBy.name")
    PaymentResponse toResponse(Payment payment);

    @Mapping(target = "userId", source = "user.id")
    @Mapping(target = "userName", source = "user.name")
    @Mapping(target = "date", source = "closeDate")
    @Mapping(target = "ticketCount", source = "ticketsCount")
    CashCloseResponse toCashCloseResponse(CashClose cashClose);

    List<CashCloseResponse> toCashCloseResponseList(List<CashClose> cashCloses);

    // Número de comprobante visible para el pasajero: RCP-<id del pago>
    default String receiptNumber(Payment payment) {
        return payment.getId() == null ? null : "RCP-" + payment.getId();
    }
}
