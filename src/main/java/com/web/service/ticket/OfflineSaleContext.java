package com.web.service.ticket;

import java.time.LocalDateTime;

// Datos de una venta hecha sin conexión: id generado por el dispositivo y hora real de la venta
public record OfflineSaleContext(String offlineClientId, LocalDateTime soldAt) {

    // Margen para relojes de dispositivo ligeramente adelantados
    public static final long CLOCK_TOLERANCE_MINUTES = 5;
}
