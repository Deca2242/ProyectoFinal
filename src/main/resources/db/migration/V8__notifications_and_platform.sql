-- Notificaciones simuladas (mock) por WhatsApp/SMS: compra, cambio de andén, llegada próxima,
-- reprogramación y cancelación de viaje

CREATE TABLE notifications (
    id BIGSERIAL PRIMARY KEY,
    -- Si se borra el usuario se borran sus notificaciones; si se borra el viaje o el ticket se conserva el registro
    user_id BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    channel VARCHAR(20) NOT NULL CHECK (channel IN ('WHATSAPP', 'SMS')),
    type VARCHAR(30) NOT NULL CHECK (
        type IN (
            'TICKET_PURCHASED',
            'PLATFORM_CHANGED',
            'ARRIVAL_SOON',
            'TRIP_RESCHEDULED',
            'TRIP_CANCELLED'
        )
    ),
    recipient VARCHAR(30) NOT NULL,
    message TEXT NOT NULL,
    trip_id BIGINT REFERENCES trips (id) ON DELETE SET NULL,
    ticket_id BIGINT REFERENCES tickets (id) ON DELETE SET NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'SENT' CHECK (status IN ('SENT', 'FAILED')),
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_notifications_user ON notifications (user_id);
CREATE INDEX IF NOT EXISTS idx_notifications_trip ON notifications (trip_id);

-- Viajes: andén de salida y marca para no repetir el aviso de llegada próxima
ALTER TABLE trips ADD COLUMN platform VARCHAR(20);
ALTER TABLE trips ADD COLUMN arrival_notified BOOLEAN NOT NULL DEFAULT FALSE;
