-- Pagos: estado de pago del ticket. Un pasajero que compra por la app queda PENDING hasta que la taquilla
-- confirma su QR/transferencia o le cobra en taquilla o al subir; las ventas del personal se cobran en el acto.
-- Los tickets existentes quedan PAID con la hora de compra como hora de pago
ALTER TABLE tickets ADD COLUMN payment_status VARCHAR(10) NOT NULL DEFAULT 'PAID'
    CHECK (payment_status IN ('PENDING', 'PAID'));
ALTER TABLE tickets ADD COLUMN paid_at TIMESTAMP;

UPDATE tickets SET paid_at = purchased_at;

-- Comprobante digital de cada cobro (uno por ticket). confirmed_by_id es quien recibió el dinero:
-- el cierre de caja de cada usuario suma los pagos que confirmó
CREATE TABLE
    payments (
        id BIGSERIAL PRIMARY KEY,
        ticket_id BIGINT NOT NULL UNIQUE REFERENCES tickets (id) ON DELETE CASCADE,
        method VARCHAR(20) NOT NULL CHECK (method IN ('CASH', 'TRANSFER', 'QR', 'CARD')),
        amount NUMERIC(10, 2) NOT NULL,
        transaction_reference VARCHAR(100),
        proof_image_url VARCHAR(500),
        paid_at TIMESTAMP NOT NULL,
        confirmed_by_id BIGINT REFERENCES users (id)
    );

CREATE INDEX IF NOT EXISTS idx_payments_confirmed_by_paid_at ON payments (confirmed_by_id, paid_at);

-- Comprobantes de los tickets ya vendidos (cobrados por quien registró la venta)
INSERT INTO payments (ticket_id, method, amount, paid_at, confirmed_by_id)
SELECT id, payment_method, price, purchased_at, sold_by_id FROM tickets;

-- Cierre de caja persistido: uno por usuario y día
CREATE TABLE
    cash_closes (
        id BIGSERIAL PRIMARY KEY,
        user_id BIGINT NOT NULL REFERENCES users (id),
        close_date DATE NOT NULL,
        expected_amount NUMERIC(12, 2) NOT NULL,
        actual_amount NUMERIC(12, 2) NOT NULL,
        difference NUMERIC(12, 2) NOT NULL,
        totals_by_method JSONB,
        tickets_count INT NOT NULL DEFAULT 0,
        refunds_total NUMERIC(12, 2) NOT NULL DEFAULT 0,
        baggage_total NUMERIC(12, 2) NOT NULL DEFAULT 0,
        notes VARCHAR(500),
        closed_at TIMESTAMP NOT NULL,
        CONSTRAINT uk_cash_closes_user_date UNIQUE (user_id, close_date)
    );

CREATE INDEX IF NOT EXISTS idx_cash_closes_date ON cash_closes (close_date);
