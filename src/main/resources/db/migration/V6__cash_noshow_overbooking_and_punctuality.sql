-- Datos necesarios para cierre de caja, no-show, métricas y overbooking aprobado

-- Tickets: reembolso y fecha de cancelación (cierre de caja), fee de no-show y canal de venta (métricas)
ALTER TABLE tickets ADD COLUMN refund_amount DECIMAL(10, 2);
ALTER TABLE tickets ADD COLUMN cancelled_at TIMESTAMP;
ALTER TABLE tickets ADD COLUMN no_show_fee DECIMAL(10, 2);
ALTER TABLE tickets ADD COLUMN channel VARCHAR(20) NOT NULL DEFAULT 'APP'
    CHECK (channel IN ('APP', 'BOX_OFFICE'));

-- Viajes: horas reales de salida/llegada (puntualidad) y sillas extra aprobadas por el DISPATCHER
ALTER TABLE trips ADD COLUMN departed_at TIMESTAMP;
ALTER TABLE trips ADD COLUMN arrived_at TIMESTAMP;
ALTER TABLE trips ADD COLUMN overbooking_approved_seats INTEGER NOT NULL DEFAULT 0
    CHECK (overbooking_approved_seats >= 0);
