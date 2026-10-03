package com.web.dto.payment;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

public record CashCloseResponse(
    Long id,
    Long userId,
    String userName,
    LocalDate date,
    BigDecimal expectedAmount,
    BigDecimal actualAmount,
    BigDecimal difference,
    Map<String, BigDecimal> totalsByMethod,  // CASH, TRANSFER, QR, CARD
    Integer ticketCount,
    BigDecimal refundsTotal,
    BigDecimal baggageTotal,
    String notes,
    LocalDateTime closedAt
) implements Serializable {}
