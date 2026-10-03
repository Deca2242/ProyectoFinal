-- Registro de abordaje: sin él, processNoShows no podía distinguir a quien ya subió al bus
-- y marcaba NO_SHOW a todos los pasajeros minutos antes de la salida.
ALTER TABLE tickets ADD COLUMN boarded_at TIMESTAMP;

-- Holds por tramo: el hold solo bloquea el asiento en el tramo solicitado,
-- igual que la venta. Los holds sin paradas (anteriores a esta migración) bloquean todo el viaje.
ALTER TABLE seat_holds ADD COLUMN from_stop_id BIGINT REFERENCES stops (id);
ALTER TABLE seat_holds ADD COLUMN to_stop_id BIGINT REFERENCES stops (id);

CREATE INDEX IF NOT EXISTS idx_seat_holds_trip_seat_status ON seat_holds (trip_id, seat_number, status);
