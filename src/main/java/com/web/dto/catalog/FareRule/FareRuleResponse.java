package com.web.dto.catalog.FareRule;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Map;

public record FareRuleResponse(
    Long id,
    Long routeId,
    Long fromStopId,
    String fromStopName,
    Integer fromStopOrder,
    Long toStopId,
    String toStopName,
    Integer toStopOrder,
    BigDecimal basePrice,
    Map<String, Object> discounts,
    Boolean dynamicPricingEnabled
) implements Serializable {}
