-- Sillas físicas de los buses existentes: la tabla seats estaba vacía y el mapa de sillas no podía
-- mostrar el tipo (STANDARD/PREFERENTIAL). Los buses nuevos generan sus sillas al crearse.
INSERT INTO seats (bus_id, seat_number, seat_type)
SELECT b.id, gs.seat_number, 'STANDARD'
FROM buses b
CROSS JOIN LATERAL generate_series(1, b.capacity) AS gs(seat_number)
ON CONFLICT (bus_id, seat_number) DO NOTHING;

-- Orden único de paradas por ruta (antes solo lo validaba el servicio). DEFERRABLE para poder
-- correr el orden de las paradas siguientes en una sola sentencia al eliminar una parada.
ALTER TABLE stops
    ADD CONSTRAINT uk_stops_route_order UNIQUE (route_id, stop_order) DEFERRABLE INITIALLY IMMEDIATE;
