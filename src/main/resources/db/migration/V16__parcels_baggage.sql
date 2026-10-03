-- Encomiendas: intentos de OTP, descripción y OTP guardado como hash (SHA-256 en hex, 64 caracteres)
ALTER TABLE parcels ADD COLUMN otp_attempts INT NOT NULL DEFAULT 0;
ALTER TABLE parcels ADD COLUMN description VARCHAR(255);
ALTER TABLE parcels ALTER COLUMN delivery_otp TYPE VARCHAR(64);

-- Los OTP existentes en claro pasan a hash con el mismo formato que OtpGenerator.hashOtp(code, otp)
UPDATE parcels
SET delivery_otp = encode(sha256(convert_to(code || ':' || delivery_otp, 'UTF8')), 'hex')
WHERE delivery_otp IS NOT NULL AND length(delivery_otp) <= 6;

-- Equipaje: maletero en el que se carga (conteo por maletero)
ALTER TABLE baggage ADD COLUMN compartment VARCHAR(20) NOT NULL DEFAULT 'MAIN';

-- Incidentes: estado OPEN/RESOLVED y quién/cuándo lo resolvió
ALTER TABLE incidents ADD COLUMN status VARCHAR(10) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'RESOLVED'));
ALTER TABLE incidents ADD COLUMN resolved_at TIMESTAMP;
ALTER TABLE incidents ADD COLUMN resolved_by BIGINT REFERENCES users (id);

CREATE INDEX IF NOT EXISTS idx_incidents_reported_by ON incidents (reported_by);

-- Notificaciones: nuevo tipo PARCEL_CREATED. Se añade al CHECK vigente sin reescribir la lista,
-- para conservar los tipos que hayan añadido otras migraciones
DO $$
DECLARE
    constraint_name TEXT;
    definition TEXT;
BEGIN
    SELECT c.conname, pg_get_constraintdef(c.oid)
    INTO constraint_name, definition
    FROM pg_constraint c
    WHERE c.conrelid = 'notifications'::regclass
      AND c.contype = 'c'
      AND pg_get_constraintdef(c.oid) LIKE '%TICKET_PURCHASED%'
    LIMIT 1;

    IF constraint_name IS NOT NULL AND position('PARCEL_CREATED' IN definition) = 0 THEN
        EXECUTE format('ALTER TABLE notifications DROP CONSTRAINT %I', constraint_name);
        EXECUTE format('ALTER TABLE notifications ADD CONSTRAINT %I %s', constraint_name,
                replace(definition, 'ARRAY[', 'ARRAY[''PARCEL_CREATED''::character varying, '));
    END IF;
END $$;
