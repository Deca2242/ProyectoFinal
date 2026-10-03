-- Límites operativos configurables por ADMIN (antes eran constantes en el código)
-- y aclaración de las claves "overbooking.percentage" / "no.show.fee.percentage", que ahora sí tienen efecto.
INSERT INTO config (config_key, config_value, description, data_type, updated_at, updated_by) VALUES
    ('baggage.weight.max', '50.0', 'Peso máximo absoluto por equipaje (kg); por encima se rechaza con 400', 'DECIMAL', NULL, NULL),
    ('parcel.otp.max.attempts', '3', 'Intentos de OTP permitidos antes de marcar la encomienda como FAILED', 'INTEGER', NULL, NULL),
    ('hold.max.per.user.trip', '4', 'Máximo de holds activos por usuario y viaje', 'INTEGER', NULL, NULL),
    ('no.show.window.minutes', '5', 'Minutos antes de la salida en que un ticket no abordado pasa a NO_SHOW', 'INTEGER', NULL, NULL),
    ('overbooking.min.occupancy', '0.95', 'Ocupación mínima (fracción) para poder aprobar overbooking', 'DECIMAL', NULL, NULL),
    ('overbooking.window.minutes', '30', 'Minutos máximos antes de la salida para poder aprobar overbooking', 'INTEGER', NULL, NULL),
    ('ticket.dynamic.pricing.default', 'false', 'Si se aplica precio dinámico en tramos sin FareRule', 'BOOLEAN', NULL, NULL)
ON CONFLICT (config_key) DO NOTHING;

UPDATE config SET description = 'Porcentaje máximo de sobreventa (misma política que overbooking.max.percentage, en fracción)'
WHERE config_key = 'overbooking.percentage';
UPDATE config SET description = 'Fee de no-show como porcentaje del precio del ticket; si es 0 se usa el monto fijo no.show.fee'
WHERE config_key = 'no.show.fee.percentage';
