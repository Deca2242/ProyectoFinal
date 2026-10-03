-- Operación offline: ventas y abordajes registrados sin red se sincronizan al recuperar la señal

-- Id que genera el dispositivo para cada venta offline: reenviar el mismo lote no duplica sillas
ALTER TABLE tickets ADD COLUMN offline_client_id VARCHAR(64) UNIQUE;
ALTER TABLE tickets ADD COLUMN synced_at TIMESTAMP;

-- Auditoría de cada lote recibido
CREATE TABLE
    sync_batches (
        id BIGSERIAL PRIMARY KEY,
        device_id VARCHAR(64) NOT NULL,
        user_id BIGINT NOT NULL REFERENCES users (id),
        type VARCHAR(20) NOT NULL CHECK (type IN ('TICKETS', 'BOARDINGS')),
        received_at TIMESTAMP NOT NULL,
        total INT NOT NULL DEFAULT 0,
        synced INT NOT NULL DEFAULT 0,
        duplicates INT NOT NULL DEFAULT 0,
        conflicts INT NOT NULL DEFAULT 0
    );

CREATE INDEX IF NOT EXISTS idx_sync_batches_user_device ON sync_batches (user_id, device_id);

-- Ventas o abordajes rechazados al sincronizar, para que la taquilla o el conductor los revisen.
-- offline_client_id guarda el id de la venta offline o el QR del abordaje
CREATE TABLE
    sync_conflicts (
        id BIGSERIAL PRIMARY KEY,
        batch_id BIGINT NOT NULL REFERENCES sync_batches (id) ON DELETE CASCADE,
        offline_client_id VARCHAR(255),
        code VARCHAR(50),
        reason TEXT,
        payload TEXT,
        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
    );

CREATE INDEX IF NOT EXISTS idx_sync_conflicts_batch ON sync_conflicts (batch_id);
