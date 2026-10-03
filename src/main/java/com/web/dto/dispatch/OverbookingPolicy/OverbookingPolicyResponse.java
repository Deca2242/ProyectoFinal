package com.web.dto.dispatch.OverbookingPolicy;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record OverbookingPolicyResponse(
    Long id,
    Long routeId,
    Integer startHour,
    Integer endHour,
    BigDecimal maxPercentage,
    Long createdById,
    String createdByName,
    LocalDateTime createdAt
) implements Serializable {}
