-- Una sola tarifa por tramo de cada ruta (antes solo lo validaba el servicio)
ALTER TABLE fare_rules
    ADD CONSTRAINT uk_fare_rules_route_segment UNIQUE (route_id, from_stop_id, to_stop_id);
