package com.web.dto.admin;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Map;

// Analis financiero
public record RevenueMetrics(
                BigDecimal totalRevenue, // Ingresos totales
                BigDecimal ticketRevenue,
                BigDecimal parcelRevenue,
                BigDecimal baggageRevenue,
                BigDecimal noShowFeeRevenue,
                Map<String, BigDecimal> revenueByPaymentMethod,
                Map<String, BigDecimal> revenueByChannel) implements Serializable {
}
