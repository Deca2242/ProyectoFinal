-- Invalidación de tokens: el filtro JWT rechaza los tokens emitidos (claim "iat") antes del último cambio de contraseña
ALTER TABLE users ADD COLUMN password_changed_at TIMESTAMP;

-- Reporte diario de puntualidad por ruta (lo genera PunctualityReportScheduler para el día anterior)
CREATE TABLE
    punctuality_reports (
        id BIGSERIAL PRIMARY KEY,
        report_date DATE NOT NULL,
        route_id BIGINT NOT NULL REFERENCES routes (id),
        trips INTEGER NOT NULL DEFAULT 0,
        on_time_departure_pct DOUBLE PRECISION,
        on_time_arrival_pct DOUBLE PRECISION,
        avg_departure_delay_min DOUBLE PRECISION,
        avg_arrival_delay_min DOUBLE PRECISION,
        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
        CONSTRAINT uk_punctuality_reports_date_route UNIQUE (report_date, route_id)
    );

CREATE INDEX IF NOT EXISTS idx_punctuality_reports_date ON punctuality_reports (report_date);
