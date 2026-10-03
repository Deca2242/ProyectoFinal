INSERT INTO
    users (
        name,
        email,
        phone,
        password_hash,
        role,
        status,
        created_at
    )
VALUES
    -- Admin
    (
        'Administrador Sistema',
        'cmbarrera@gmail.com',
        '3001000001',
        '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
        'ADMIN',
        'ACTIVE',
        CURRENT_TIMESTAMP
    ),
    -- Pasajeros
    (
        'Juan Pérez',
        'juan.perez@email.com',
        '3001111111',
        '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
        'PASSENGER',
        'ACTIVE',
        CURRENT_TIMESTAMP
    ),
    (
        'María López',
        'maria.lopez@email.com',
        '3002222222',
        '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
        'PASSENGER',
        'ACTIVE',
        CURRENT_TIMESTAMP
    ),
    (
        'Carlos Rodríguez',
        'carlos.rodriguez@email.com',
        '3003333333',
        '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
        'PASSENGER',
        'ACTIVE',
        CURRENT_TIMESTAMP
    ),
    (
        'Ana García',
        'ana.garcia@email.com',
        '3004444444',
        '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
        'PASSENGER',
        'ACTIVE',
        CURRENT_TIMESTAMP
    ),
    -- Taquilleros
    (
        'Pedro Taquillero',
        'clerk1@transport.com',
        '3005555555',
        '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
        'CLERK',
        'ACTIVE',
        CURRENT_TIMESTAMP
    ),
    (
        'Laura Taquillera',
        'clerk2@transport.com',
        '3006666666',
        '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
        'CLERK',
        'ACTIVE',
        CURRENT_TIMESTAMP
    ),
    -- Conductores
    (
        'Roberto Conductor',
        'driver1@transport.com',
        '3007777777',
        '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
        'DRIVER',
        'ACTIVE',
        CURRENT_TIMESTAMP
    ),
    (
        'Sergio Conductor',
        'driver2@transport.com',
        '3008888888',
        '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
        'DRIVER',
        'ACTIVE',
        CURRENT_TIMESTAMP
    ),
    (
        'Miguel Conductor',
        'driver3@transport.com',
        '3009999999',
        '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
        'DRIVER',
        'ACTIVE',
        CURRENT_TIMESTAMP
    ),
    -- Despachadores
    (
        'Andrea Despachadora',
        'dispatcher1@transport.com',
        '3010101010',
        '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
        'DISPATCHER',
        'ACTIVE',
        CURRENT_TIMESTAMP
    ),
    (
        'Jorge Despachador',
        'dispatcher2@transport.com',
        '3011111111',
        '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
        'DISPATCHER',
        'ACTIVE',
        CURRENT_TIMESTAMP
    );

-- =====================================================
-- 2. BUSES
-- =====================================================
INSERT INTO
    buses (plate, capacity, amenities, status)
VALUES
    -- Buses Premium (50 asientos)
    (
        'ABC123',
        50,
        '{"wifi": true, "airConditioning": true, "bathroom": true, "tv": true, "usb": true, "reclining": true}',
        'ACTIVE'
    ),
    (
        'DEF456',
        50,
        '{"wifi": true, "airConditioning": true, "bathroom": true, "tv": true, "usb": true, "reclining": true}',
        'ACTIVE'
    ),
    -- Buses Estándar (45 asientos)
    (
        'GHI789',
        45,
        '{"wifi": true, "airConditioning": true, "bathroom": true, "tv": false, "usb": true}',
        'ACTIVE'
    ),
    (
        'JKL012',
        45,
        '{"wifi": true, "airConditioning": true, "bathroom": true, "tv": false, "usb": true}',
        'ACTIVE'
    ),
    (
        'MNO345',
        45,
        '{"wifi": true, "airConditioning": true, "bathroom": true, "tv": false, "usb": true}',
        'ACTIVE'
    ),
    -- Buses Económicos (40 asientos)
    (
        'PQR678',
        40,
        '{"wifi": false, "airConditioning": true, "bathroom": false, "tv": false, "usb": false}',
        'ACTIVE'
    ),
    (
        'STU901',
        40,
        '{"wifi": false, "airConditioning": true, "bathroom": false, "tv": false, "usb": false}',
        'ACTIVE'
    ),
    -- Bus en mantenimiento
    (
        'VWX234',
        45,
        '{"wifi": true, "airConditioning": true, "bathroom": true, "tv": true, "usb": true}',
        'MAINTENANCE'
    );

-- =====================================================
-- 3. RUTAS
-- =====================================================
INSERT INTO
    routes (
        code,
        name,
        origin,
        destination,
        distance_km,
        duration_min,
        is_active
    )
VALUES
    -- Rutas principales
    (
        'BOG-MDE',
        'Bogotá - Medellín',
        'Bogotá',
        'Medellín',
        415.0,
        540,
        true
    ),
    (
        'BOG-CAL',
        'Bogotá - Cali',
        'Bogotá',
        'Cali',
        460.0,
        600,
        true
    ),
    (
        'MDE-CTG',
        'Medellín - Cartagena',
        'Medellín',
        'Cartagena',
        630.0,
        780,
        true
    ),
    (
        'BOG-BAQ',
        'Bogotá - Barranquilla',
        'Bogotá',
        'Barranquilla',
        985.0,
        960,
        true
    ),
    (
        'CAL-MDE',
        'Cali - Medellín',
        'Cali',
        'Medellín',
        420.0,
        560,
        true
    );

-- =====================================================
-- 4. PARADAS (STOPS)
-- =====================================================
-- Paradas para BOG-MDE (Ruta 1)
INSERT INTO
    stops (route_id, name, stop_order, latitude, longitude)
VALUES
    (1, 'Terminal Bogotá', 1, 4.609700, -74.081700),
    (1, 'Girardot', 2, 4.300300, -74.802700),
    (1, 'Honda', 3, 5.208900, -74.735900),
    (1, 'La Dorada', 4, 5.451100, -74.657800),
    (1, 'Puerto Triunfo', 5, 5.877200, -74.639700),
    (1, 'Terminal Medellín', 6, 6.244200, -75.581200);

-- Paradas para BOG-CAL (Ruta 2)
INSERT INTO
    stops (route_id, name, stop_order, latitude, longitude)
VALUES
    (2, 'Terminal Bogotá Sur', 1, 4.609700, -74.081700),
    (2, 'Fusagasugá', 2, 4.336700, -74.363900),
    (2, 'Girardot', 3, 4.300300, -74.802700),
    (2, 'Ibagué', 4, 4.438900, -75.232200),
    (2, 'Armenia', 5, 4.533900, -75.681100),
    (2, 'Pereira', 6, 4.813300, -75.696100),
    (2, 'Terminal Cali', 7, 3.451600, -76.532000);

-- Paradas para MDE-CTG (Ruta 3)
INSERT INTO
    stops (route_id, name, stop_order, latitude, longitude)
VALUES
    (
        3,
        'Terminal Medellín Norte',
        1,
        6.244200,
        -75.581200
    ),
    (3, 'Caucasia', 2, 7.987800, -75.194400),
    (3, 'Montería', 3, 8.747900, -75.881700),
    (3, 'Sincelejo', 4, 9.304700, -75.397800),
    (3, 'Terminal Cartagena', 5, 10.391700, -75.479400);

-- Paradas para BOG-BAQ (Ruta 4)
INSERT INTO
    stops (route_id, name, stop_order, latitude, longitude)
VALUES
    (
        4,
        'Terminal Bogotá Norte',
        1,
        4.609700,
        -74.081700
    ),
    (4, 'Tunja', 2, 5.535300, -73.367800),
    (4, 'Bucaramanga', 3, 7.119400, -73.122800),
    (4, 'Valledupar', 4, 10.463900, -73.250600),
    (
        4,
        'Terminal Barranquilla',
        5,
        10.963900,
        -74.796400
    );

-- Paradas para CAL-MDE (Ruta 5)
INSERT INTO
    stops (route_id, name, stop_order, latitude, longitude)
VALUES
    (5, 'Terminal Cali Norte', 1, 3.451600, -76.532000),
    (5, 'Cartago', 2, 4.746700, -75.911700),
    (5, 'Pereira', 3, 4.813300, -75.696100),
    (5, 'Manizales', 4, 5.070300, -75.513900),
    (
        5,
        'Terminal Medellín Sur',
        5,
        6.244200,
        -75.581200
    );

-- =====================================================
-- 5. REGLAS DE TARIFA (FARE RULES)
-- =====================================================
INSERT INTO
    fare_rules (
        route_id,
        from_stop_id,
        to_stop_id,
        base_price,
        discounts,
        dynamic_pricing_enabled
    )
VALUES
    -- Ruta 1: Bogotá - Medellín
    (
        1,
        1,
        6,
        75000.00,
        '{"STUDENT":10, "SENIOR":15}',
        true
    ),
    (1, 1, 3, 35000.00, '{"STUDENT":5}', false),
    (1, 3, 6, 40000.00, '{"SENIOR":10}', false),
    -- Ruta 2: Bogotá - Cali
    (2, 7, 13, 85000.00, '{"STUDENT":12}', true),
    (2, 7, 11, 60000.00, '{"SENIOR":15}', false),
    (2, 9, 13, 45000.00, NULL, false),
    -- Ruta 3: Medellín - Cartagena
    (
        3,
        14,
        18,
        90000.00,
        '{"STUDENT":10, "CHILD":50}',
        true
    ),
    (3, 14, 16, 55000.00, '{"SENIOR":15}', false),
    -- Ruta 4: Bogotá - Barranquilla
    (4, 19, 23, 120000.00, '{"STUDENT":8}', true);

-- =====================================================
-- 6. VIAJES (TRIPS) - Próximos días
-- =====================================================
-- Viajes para BOG-MDE (Ruta 1)
INSERT INTO
    trips (
        route_id,
        bus_id,
        trip_date,
        departure_time,
        arrival_eta,
        status,
        created_at
    )
VALUES
    -- Hoy
    (
        1,
        1,
        CURRENT_DATE,
        CURRENT_DATE + TIME '06:00:00',
        CURRENT_DATE + TIME '15:00:00',
        'SCHEDULED',
        CURRENT_TIMESTAMP
    ),
    (
        1,
        3,
        CURRENT_DATE,
        CURRENT_DATE + TIME '10:00:00',
        CURRENT_DATE + TIME '19:00:00',
        'SCHEDULED',
        CURRENT_TIMESTAMP
    ),
    (
        1,
        4,
        CURRENT_DATE,
        CURRENT_DATE + TIME '14:00:00',
        CURRENT_DATE + TIME '23:00:00',
        'SCHEDULED',
        CURRENT_TIMESTAMP
    ),
    (
        1,
        2,
        CURRENT_DATE,
        CURRENT_DATE + TIME '22:00:00',
        CURRENT_DATE + INTERVAL '1 day' + TIME '07:00:00',
        'SCHEDULED',
        CURRENT_TIMESTAMP
    ),
    -- Mañana
    (
        1,
        1,
        CURRENT_DATE + INTERVAL '1 day',
        CURRENT_DATE + INTERVAL '1 day' + TIME '06:00:00',
        CURRENT_DATE + INTERVAL '1 day' + TIME '15:00:00',
        'SCHEDULED',
        CURRENT_TIMESTAMP
    ),
    (
        1,
        3,
        CURRENT_DATE + INTERVAL '1 day',
        CURRENT_DATE + INTERVAL '1 day' + TIME '10:00:00',
        CURRENT_DATE + INTERVAL '1 day' + TIME '19:00:00',
        'SCHEDULED',
        CURRENT_TIMESTAMP
    ),
    (
        1,
        5,
        CURRENT_DATE + INTERVAL '1 day',
        CURRENT_DATE + INTERVAL '1 day' + TIME '14:00:00',
        CURRENT_DATE + INTERVAL '1 day' + TIME '23:00:00',
        'SCHEDULED',
        CURRENT_TIMESTAMP
    ),
    -- Pasado mañana
    (
        1,
        2,
        CURRENT_DATE + INTERVAL '2 days',
        CURRENT_DATE + INTERVAL '2 days' + TIME '06:00:00',
        CURRENT_DATE + INTERVAL '2 days' + TIME '15:00:00',
        'SCHEDULED',
        CURRENT_TIMESTAMP
    ),
    (
        1,
        4,
        CURRENT_DATE + INTERVAL '2 days',
        CURRENT_DATE + INTERVAL '2 days' + TIME '10:00:00',
        CURRENT_DATE + INTERVAL '2 days' + TIME '19:00:00',
        'SCHEDULED',
        CURRENT_TIMESTAMP
    );

-- Viajes para BOG-CAL (Ruta 2)
INSERT INTO
    trips (
        route_id,
        bus_id,
        trip_date,
        departure_time,
        arrival_eta,
        status,
        created_at
    )
VALUES
    -- Hoy
    (
        2,
        5,
        CURRENT_DATE,
        CURRENT_DATE + TIME '07:00:00',
        CURRENT_DATE + TIME '17:00:00',
        'SCHEDULED',
        CURRENT_TIMESTAMP
    ),
    (
        2,
        6,
        CURRENT_DATE,
        CURRENT_DATE + TIME '15:00:00',
        CURRENT_DATE + INTERVAL '1 day' + TIME '01:00:00',
        'SCHEDULED',
        CURRENT_TIMESTAMP
    ),
    -- Mañana
    (
        2,
        5,
        CURRENT_DATE + INTERVAL '1 day',
        CURRENT_DATE + INTERVAL '1 day' + TIME '07:00:00',
        CURRENT_DATE + INTERVAL '1 day' + TIME '17:00:00',
        'SCHEDULED',
        CURRENT_TIMESTAMP
    ),
    (
        2,
        7,
        CURRENT_DATE + INTERVAL '1 day',
        CURRENT_DATE + INTERVAL '1 day' + TIME '15:00:00',
        CURRENT_DATE + INTERVAL '2 days' + TIME '01:00:00',
        'SCHEDULED',
        CURRENT_TIMESTAMP
    );

-- Viajes para MDE-CTG (Ruta 3)
INSERT INTO
    trips (
        route_id,
        bus_id,
        trip_date,
        departure_time,
        arrival_eta,
        status,
        created_at
    )
VALUES
    -- Hoy
    (
        3,
        2,
        CURRENT_DATE,
        CURRENT_DATE + TIME '08:00:00',
        CURRENT_DATE + TIME '21:00:00',
        'SCHEDULED',
        CURRENT_TIMESTAMP
    ),
    -- Mañana
    (
        3,
        1,
        CURRENT_DATE + INTERVAL '1 day',
        CURRENT_DATE + INTERVAL '1 day' + TIME '08:00:00',
        CURRENT_DATE + INTERVAL '1 day' + TIME '21:00:00',
        'SCHEDULED',
        CURRENT_TIMESTAMP
    );

-- Viajes para BOG-BAQ (Ruta 4)
INSERT INTO
    trips (
        route_id,
        bus_id,
        trip_date,
        departure_time,
        arrival_eta,
        status,
        created_at
    )
VALUES
    -- Hoy
    (
        4,
        3,
        CURRENT_DATE,
        CURRENT_DATE + TIME '20:00:00',
        CURRENT_DATE + INTERVAL '1 day' + TIME '12:00:00',
        'SCHEDULED',
        CURRENT_TIMESTAMP
    ),
    -- Mañana
    (
        4,
        4,
        CURRENT_DATE + INTERVAL '1 day',
        CURRENT_DATE + INTERVAL '1 day' + TIME '20:00:00',
        CURRENT_DATE + INTERVAL '2 days' + TIME '12:00:00',
        'SCHEDULED',
        CURRENT_TIMESTAMP
    );

-- Viajes para CAL-MDE (Ruta 5)
INSERT INTO
    trips (
        route_id,
        bus_id,
        trip_date,
        departure_time,
        arrival_eta,
        status,
        created_at
    )
VALUES
    -- Hoy
    (
        5,
        6,
        CURRENT_DATE,
        CURRENT_DATE + TIME '09:00:00',
        CURRENT_DATE + TIME '18:00:00',
        'SCHEDULED',
        CURRENT_TIMESTAMP
    ),
    -- Mañana
    (
        5,
        7,
        CURRENT_DATE + INTERVAL '1 day',
        CURRENT_DATE + INTERVAL '1 day' + TIME '09:00:00',
        CURRENT_DATE + INTERVAL '1 day' + TIME '18:00:00',
        'SCHEDULED',
        CURRENT_TIMESTAMP
    );

-- =====================================================
-- 7. ASIGNACIONES DE CONDUCTORES (para algunos viajes)
-- =====================================================
INSERT INTO
    assignments (
        trip_id,
        driver_id,
        dispatcher_id,
        checklist_ok,
        soat_valid,
        revision_valid,
        assigned_at
    )
VALUES
    -- Viajes de hoy ya asignados
    (1, 8, 11, true, true, true, CURRENT_TIMESTAMP),
    (2, 9, 11, true, true, true, CURRENT_TIMESTAMP),
    (3, 10, 11, true, true, false, CURRENT_TIMESTAMP),
    (10, 8, 12, true, true, true, CURRENT_TIMESTAMP),
    (14, 9, 12, true, false, true, CURRENT_TIMESTAMP);

-- =====================================================
-- 8. TICKETS DE EJEMPLO (algunos viajes ya con ventas)
-- =====================================================
-- Tickets para Trip 1 (BOG-MDE hoy 6am)
INSERT INTO
    tickets (
        trip_id,
        passenger_id,
        seat_number,
        from_stop_id,
        to_stop_id,
        price,
        payment_method,
        status,
        qr_code,
        purchased_at
    )
VALUES
    -- Viaje completo
    (
        1,
        2,
        1,
        1,
        6,
        75000.00,
        'CARD',
        'SOLD',
        'TKT-001-BOG-MDE-001',
        CURRENT_TIMESTAMP - INTERVAL '2 days'
    ),
    (
        1,
        3,
        2,
        1,
        6,
        75000.00,
        'CASH',
        'SOLD',
        'TKT-001-BOG-MDE-002',
        CURRENT_TIMESTAMP - INTERVAL '2 days'
    ),
    (
        1,
        4,
        5,
        1,
        6,
        75000.00,
        'TRANSFER',
        'SOLD',
        'TKT-001-BOG-MDE-005',
        CURRENT_TIMESTAMP - INTERVAL '1 day'
    ),
    (
        1,
        5,
        8,
        1,
        6,
        60000.00,
        'CARD',
        'SOLD',
        'TKT-001-BOG-MDE-008',
        CURRENT_TIMESTAMP - INTERVAL '1 day'
    ),
    -- Tramos parciales
    (
        1,
        2,
        10,
        1,
        3,
        35000.00,
        'CARD',
        'SOLD',
        'TKT-001-BOG-MDE-010',
        CURRENT_TIMESTAMP - INTERVAL '1 day'
    ),
    (
        1,
        3,
        15,
        3,
        6,
        40000.00,
        'CASH',
        'SOLD',
        'TKT-001-BOG-MDE-015',
        CURRENT_TIMESTAMP - INTERVAL '1 day'
    ),
    (
        1,
        4,
        20,
        1,
        4,
        45000.00,
        'CARD',
        'SOLD',
        'TKT-001-BOG-MDE-020',
        CURRENT_TIMESTAMP - INTERVAL '6 hours'
    );

-- Tickets para Trip 2 (BOG-MDE hoy 10am)
INSERT INTO
    tickets (
        trip_id,
        passenger_id,
        seat_number,
        from_stop_id,
        to_stop_id,
        price,
        payment_method,
        status,
        qr_code,
        purchased_at
    )
VALUES
    (
        2,
        2,
        3,
        1,
        6,
        75000.00,
        'CARD',
        'SOLD',
        'TKT-002-BOG-MDE-003',
        CURRENT_TIMESTAMP - INTERVAL '1 day'
    ),
    (
        2,
        5,
        7,
        1,
        6,
        60000.00,
        'CARD',
        'SOLD',
        'TKT-002-BOG-MDE-007',
        CURRENT_TIMESTAMP - INTERVAL '12 hours'
    ),
    (
        2,
        4,
        12,
        1,
        6,
        75000.00,
        'TRANSFER',
        'SOLD',
        'TKT-002-BOG-MDE-012',
        CURRENT_TIMESTAMP - INTERVAL '6 hours'
    );

-- Tickets para Trip 10 (BOG-CAL hoy 7am)
INSERT INTO
    tickets (
        trip_id,
        passenger_id,
        seat_number,
        from_stop_id,
        to_stop_id,
        price,
        payment_method,
        status,
        qr_code,
        purchased_at
    )
VALUES
    (
        10,
        2,
        5,
        7,
        13,
        85000.00,
        'CARD',
        'SOLD',
        'TKT-010-BOG-CAL-005',
        CURRENT_TIMESTAMP - INTERVAL '2 days'
    ),
    (
        10,
        3,
        10,
        7,
        13,
        85000.00,
        'CASH',
        'SOLD',
        'TKT-010-BOG-CAL-010',
        CURRENT_TIMESTAMP - INTERVAL '1 day'
    ),
    (
        10,
        4,
        15,
        7,
        11,
        50000.00,
        'CARD',
        'SOLD',
        'TKT-010-BOG-CAL-015',
        CURRENT_TIMESTAMP - INTERVAL '1 day'
    );

-- =====================================================
-- 9. EQUIPAJE (BAGGAGE) para algunos tickets
-- =====================================================
INSERT INTO
    baggage (ticket_id, weight_kg, excess_fee, tag_code)
VALUES
    -- Para tickets del trip 1
    (1, 18.5, 0.00, 'BAG-001-01'),
    (2, 22.0, 5000.00, 'BAG-002-01'),
    (3, 15.0, 0.00, 'BAG-003-01'),
    (4, 25.0, 12500.00, 'BAG-004-01'),
    -- Para tickets del trip 2
    (8, 19.0, 0.00, 'BAG-008-01'),
    (9, 17.5, 0.00, 'BAG-009-01'),
    -- Para tickets del trip 10
    (11, 20.0, 0.00, 'BAG-011-01'),
    (12, 23.5, 8750.00, 'BAG-012-01');

-- =====================================================
-- 10. ENCOMIENDAS (PARCELS)
-- =====================================================
INSERT INTO
    parcels (
        code,
        sender_name,
        sender_phone,
        receiver_name,
        receiver_phone,
        from_stop_id,
        to_stop_id,
        trip_id,
        weight_kg,
        price,
        status,
        delivery_otp,
        proof_photo_url,
        created_at
    )
VALUES
    -- Encomiendas en tránsito
    (
        'PRC-2025-001',
        'Empresa ABC Ltda',
        '3101111111',
        'Comercial XYZ',
        '3202222222',
        1,
        6,
        1,
        8.5,
        25000.00,
        'IN_TRANSIT',
        '123456',
        NULL,
        CURRENT_TIMESTAMP - INTERVAL '1 day'
    ),
    (
        'PRC-2025-002',
        'Rosa Martínez',
        '3103333333',
        'Pedro López',
        '3204444444',
        1,
        3,
        1,
        5.0,
        15000.00,
        'IN_TRANSIT',
        '234567',
        NULL,
        CURRENT_TIMESTAMP - INTERVAL '1 day'
    ),
    (
        'PRC-2025-003',
        'Farmacia Salud',
        '3105555555',
        'Hospital Central',
        '3206666666',
        7,
        13,
        10,
        3.5,
        18000.00,
        'IN_TRANSIT',
        '345678',
        NULL,
        CURRENT_TIMESTAMP - INTERVAL '12 hours'
    ),
    -- Encomiendas entregadas (trip_id se refiere a viajes ya completados)
    (
        'PRC-2025-004',
        'Juan Comercio',
        '3107777777',
        'María Tienda',
        '3208888888',
        1,
        6,
        1,
        12.0,
        35000.00,
        'DELIVERED',
        '456789',
        'https://storage.example.com/proof/004.jpg',
        CURRENT_TIMESTAMP - INTERVAL '3 days'
    ),
    (
        'PRC-2025-005',
        'Carlos Sender',
        '3109999999',
        'Ana Receiver',
        '3200000000',
        7,
        13,
        10,
        6.0,
        20000.00,
        'DELIVERED',
        '567890',
        'https://storage.example.com/proof/005.jpg',
        CURRENT_TIMESTAMP - INTERVAL '5 days'
    ),
    -- Encomiendas creadas pendientes
    (
        'PRC-2025-006',
        'Textiles Colombia',
        '3101010101',
        'Modas Medellín',
        '3202020202',
        1,
        6,
        5,
        15.0,
        45000.00,
        'CREATED',
        '678901',
        NULL,
        CURRENT_TIMESTAMP - INTERVAL '6 hours'
    ),
    (
        'PRC-2025-007',
        'Librería Central',
        '3103030303',
        'Universidad del Valle',
        '3204040404',
        7,
        13,
        11,
        10.0,
        30000.00,
        'CREATED',
        '789012',
        NULL,
        CURRENT_TIMESTAMP - INTERVAL '3 hours'
    );

-- =====================================================
-- 11. HOLDS DE ASIENTOS (algunos activos)
-- =====================================================
-- Holds activos (expiran en los próximos minutos)
INSERT INTO
    seat_holds (
        trip_id,
        seat_number,
        user_id,
        expires_at,
        status,
        created_at
    )
VALUES
    (
        3,
        25,
        2,
        CURRENT_TIMESTAMP + INTERVAL '8 minutes',
        'HOLD',
        CURRENT_TIMESTAMP
    ),
    (
        3,
        26,
        3,
        CURRENT_TIMESTAMP + INTERVAL '5 minutes',
        'HOLD',
        CURRENT_TIMESTAMP
    ),
    (
        5,
        18,
        4,
        CURRENT_TIMESTAMP + INTERVAL '9 minutes',
        'HOLD',
        CURRENT_TIMESTAMP
    );

-- Holds expirados
INSERT INTO
    seat_holds (
        trip_id,
        seat_number,
        user_id,
        expires_at,
        status,
        created_at
    )
VALUES
    (
        1,
        30,
        2,
        CURRENT_TIMESTAMP - INTERVAL '2 hours',
        'EXPIRED',
        CURRENT_TIMESTAMP - INTERVAL '2 hours 10 minutes'
    ),
    (
        2,
        35,
        3,
        CURRENT_TIMESTAMP - INTERVAL '1 hour',
        'EXPIRED',
        CURRENT_TIMESTAMP - INTERVAL '1 hour 10 minutes'
    );

-- =====================================================
-- 12. CONFIGURACIÓN DEL SISTEMA
-- =====================================================
INSERT INTO
    config (
        config_key,
        config_value,
        description,
        data_type,
        updated_at,
        updated_by
    )
VALUES
    (
        'hold.duration.minutes',
        '10',
        'Duración de los holds de asientos en minutos',
        'INTEGER',
        NULL,
        NULL
    ),
    (
        'overbooking.percentage',
        '5',
        'Porcentaje máximo de sobreventa mostrado en panel administrativo',
        'INTEGER',
        NULL,
        NULL
    ),
    (
        'no.show.fee.percentage',
        '10',
        'Porcentaje de penalización aplicado a no-shows',
        'INTEGER',
        NULL,
        NULL
    ),
    (
        'baggage.weight.limit',
        '20.0',
        'Peso permitido por equipaje antes de cobrar exceso (kg)',
        'DECIMAL',
        NULL,
        NULL
    ),
    (
        'baggage.price.per.kg',
        '2500',
        'Tarifa por kilogramo de exceso de equipaje',
        'DECIMAL',
        NULL,
        NULL
    ),
    (
        'no.show.fee',
        '10000',
        'Valor monetario de la multa por no-show',
        'DECIMAL',
        NULL,
        NULL
    ),
    (
        'overbooking.max.percentage',
        '0.05',
        'Porcentaje máximo permitido para cálculos de sobreventa',
        'DECIMAL',
        NULL,
        NULL
    ),
    (
        'refund.policy.48hours.percentage',
        '90',
        'Porcentaje de reembolso si cancelan con al menos 48 horas',
        'DECIMAL',
        NULL,
        NULL
    ),
    (
        'refund.policy.24hours.percentage',
        '70',
        'Porcentaje de reembolso si cancelan entre 24 y 48 horas',
        'DECIMAL',
        NULL,
        NULL
    ),
    (
        'refund.policy.12hours.percentage',
        '50',
        'Porcentaje de reembolso si cancelan entre 12 y 24 horas',
        'DECIMAL',
        NULL,
        NULL
    ),
    (
        'refund.policy.6hours.percentage',
        '30',
        'Porcentaje de reembolso si cancelan entre 6 y 12 horas',
        'DECIMAL',
        NULL,
        NULL
    ),
    (
        'refund.policy.less.6hours.percentage',
        '0',
        'Porcentaje de reembolso si cancelan con menos de 6 horas',
        'DECIMAL',
        NULL,
        NULL
    ),
    (
        'ticket.base.price',
        '50000',
        'Precio base utilizado cuando no hay tarifa definida',
        'DECIMAL',
        NULL,
        NULL
    ),
    (
        'ticket.price.multiplier.peak.hours',
        '1.15',
        'Multiplicador de precio para horas pico',
        'DECIMAL',
        NULL,
        NULL
    ),
    (
        'ticket.price.multiplier.high.demand',
        '1.2',
        'Multiplicador de precio para alta demanda',
        'DECIMAL',
        NULL,
        NULL
    ),
    (
        'ticket.price.multiplier.medium.demand',
        '1.1',
        'Multiplicador de precio para demanda media',
        'DECIMAL',
        NULL,
        NULL
    );

-- =====================================================
-- 13. INCIDENTES DE EJEMPLO
-- =====================================================
INSERT INTO
    incidents (
        entity_type,
        entity_id,
        incident_type,
        description,
        created_at
    )
VALUES
    (
        'PARCEL',
        4,
        'DELIVERY_FAIL',
        'Primera entrega fallida - destinatario no disponible',
        CURRENT_TIMESTAMP - INTERVAL '3 days'
    ),
    (
        'TRIP',
        1,
        'VEHICLE',
        'Retraso de 15 minutos por tráfico en peaje',
        CURRENT_TIMESTAMP - INTERVAL '1 day'
    ),
    (
        'TICKET',
        2,
        'OVERBOOK',
        'Intento de sobreventa detectado y rechazado',
        CURRENT_TIMESTAMP - INTERVAL '2 days'
    );