-- Usuario que registró la venta (taquillero, conductor o el propio pasajero en la app):
-- permite cuadrar la caja de cada cajero por separado
ALTER TABLE tickets ADD COLUMN sold_by_id BIGINT REFERENCES users (id);

CREATE INDEX IF NOT EXISTS idx_tickets_sold_by ON tickets (sold_by_id);
