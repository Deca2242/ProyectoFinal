-- Despacho, notificaciones y sincronización offline

-- Checklist del bus con vigencia: si hay fechas, el SOAT y la revisión técnica deben cubrir el día del viaje;
-- si son NULL se usan los booleanos soat_valid / revision_valid de la asignación
ALTER TABLE buses ADD COLUMN soat_expires_at DATE;
ALTER TABLE buses ADD COLUMN technical_review_expires_at DATE;

-- Cierre real del abordaje (NULL = abordaje abierto o aún no abierto)
ALTER TABLE trips ADD COLUMN boarding_closed_at TIMESTAMP;

-- Notificación leída por su destinatario (NULL = no leída)
ALTER TABLE notifications ADD COLUMN IF NOT EXISTS read_at TIMESTAMP;
CREATE INDEX IF NOT EXISTS idx_notifications_user_unread ON notifications (user_id) WHERE read_at IS NULL;

-- Conflictos de sincronización revisados por la taquilla, el despacho o el ADMIN
ALTER TABLE sync_conflicts ADD COLUMN resolved_at TIMESTAMP;
ALTER TABLE sync_conflicts ADD COLUMN resolved_by BIGINT REFERENCES users (id) ON DELETE SET NULL;
CREATE INDEX IF NOT EXISTS idx_sync_conflicts_open ON sync_conflicts (batch_id) WHERE resolved_at IS NULL;
