package com.web.dto.payment;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

// POST /api/v1/cash/close: el usuario que cierra es siempre el del token (CLERK o DRIVER)
public record CashCloseRequest(
    @NotNull @PastOrPresent LocalDate date,
    BigDecimal expectedAmount,  // Se ignora: el total esperado lo calcula el sistema a partir de los pagos del día
    @NotNull @PositiveOrZero BigDecimal actualAmount,  // Efectivo contado en caja
    @Size(max = 500) String notes
) implements Serializable {}
