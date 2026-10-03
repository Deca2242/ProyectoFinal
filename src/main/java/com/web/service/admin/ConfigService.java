package com.web.service.admin;

import com.web.dto.admin.ConfigResponse;
import com.web.dto.admin.ConfigUpdateRequest;

import java.math.BigDecimal;
import java.util.Map;

public interface ConfigService {

    ConfigResponse getConfig();

    ConfigResponse updateConfig(ConfigUpdateRequest request, Long adminUserId);

    Integer getHoldDurationMinutes();

    Double getBaggageWeightLimit();

    BigDecimal getExcessFeePerKg();

    BigDecimal getNoShowFee();

    Double getOverbookingMaxPercentage();

    // Políticas de Reembolso
    BigDecimal getRefundPercentage48Hours();

    BigDecimal getRefundPercentage24Hours();

    BigDecimal getRefundPercentage12Hours();

    BigDecimal getRefundPercentage6Hours();

    BigDecimal getRefundPercentageLess6Hours();

    // Precios de Tickets
    BigDecimal getTicketBasePrice();

    BigDecimal getTicketPriceMultiplierPeakHours();

    BigDecimal getTicketPriceMultiplierHighDemand();

    BigDecimal getTicketPriceMultiplierMediumDemand();

    // Descuentos
    Map<String, Integer> getDiscountPercentages();

    Integer getDiscountPercentage(String discountType, Integer fallback);

    // Límites operativos (todos configurables por ADMIN; valores por defecto del documento del proyecto)
    Double getBaggageWeightMax();

    Integer getParcelOtpMaxAttempts();

    Integer getMaxActiveHoldsPerUserAndTrip();

    Integer getNoShowWindowMinutes();

    Double getOverbookingMinOccupancy();

    Integer getOverbookingWindowMinutes();

    boolean isDynamicPricingDefault();

    // Fee de no-show: porcentaje del precio del ticket si "no.show.fee.percentage" > 0, si no el monto fijo "no.show.fee"
    BigDecimal computeNoShowFee(BigDecimal ticketPrice);
}
