-- Porcentaje máximo de overbooking por ruta y franja horaria (hora de salida en [start_hour, end_hour)).
-- Si ninguna franja de la ruta contiene la hora de salida se usa overbooking.max.percentage de config.
-- El solapamiento de franjas de una misma ruta se valida en el servicio (409).
CREATE TABLE
    overbooking_policies (
        id BIGSERIAL PRIMARY KEY,
        route_id BIGINT NOT NULL REFERENCES routes (id),
        start_hour INTEGER NOT NULL CHECK (start_hour BETWEEN 0 AND 23),
        end_hour INTEGER NOT NULL CHECK (end_hour BETWEEN 1 AND 24),
        max_percentage NUMERIC(5, 4) NOT NULL CHECK (max_percentage BETWEEN 0 AND 1),
        created_by BIGINT REFERENCES users (id),
        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
        CONSTRAINT chk_overbooking_policy_hours CHECK (start_hour < end_hour)
    );

CREATE INDEX IF NOT EXISTS idx_overbooking_policies_route ON overbooking_policies (route_id);
