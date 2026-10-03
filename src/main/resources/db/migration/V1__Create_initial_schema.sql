-- Users table
CREATE TABLE
    users (
        id BIGSERIAL PRIMARY KEY,
        name VARCHAR(100) NOT NULL,
        email VARCHAR(150) NOT NULL UNIQUE,
        phone VARCHAR(20),
        role VARCHAR(20) NOT NULL CHECK (
            role IN (
                'PASSENGER',
                'CLERK',
                'DRIVER',
                'DISPATCHER',
                'ADMIN'
            )
        ),
        password_hash VARCHAR(255) NOT NULL,
        status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
    );

-- Routes table
CREATE TABLE
    routes (
        id BIGSERIAL PRIMARY KEY,
        code VARCHAR(20) NOT NULL UNIQUE,
        name VARCHAR(100) NOT NULL,
        origin VARCHAR(100) NOT NULL,
        destination VARCHAR(100) NOT NULL,
        distance_km DECIMAL(8, 2) NOT NULL,
        duration_min INTEGER NOT NULL,
        is_active BOOLEAN NOT NULL DEFAULT TRUE,
        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
    );

-- Stops table
CREATE TABLE
    stops (
        id BIGSERIAL PRIMARY KEY,
        route_id BIGINT NOT NULL REFERENCES routes (id),
        name VARCHAR(100) NOT NULL,
        stop_order INTEGER NOT NULL,
        latitude DECIMAL(10, 8),
        longitude DECIMAL(11, 8)
    );

-- Buses table
CREATE TABLE
    buses (
        id BIGSERIAL PRIMARY KEY,
        plate VARCHAR(20) NOT NULL UNIQUE,
        capacity INTEGER NOT NULL,
        amenities JSONB,
        status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'MAINTENANCE', 'RETIRED')),
        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
    );

-- Seats table
CREATE TABLE
    seats (
        id BIGSERIAL PRIMARY KEY,
        bus_id BIGINT NOT NULL REFERENCES buses (id),
        seat_number INTEGER NOT NULL,
        seat_type VARCHAR(20) NOT NULL CHECK (seat_type IN ('STANDARD', 'PREFERENTIAL')),
        UNIQUE (bus_id, seat_number)
    );

-- Trips table
CREATE TABLE
    trips (
        id BIGSERIAL PRIMARY KEY,
        route_id BIGINT NOT NULL REFERENCES routes (id),
        bus_id BIGINT NOT NULL REFERENCES buses (id),
        trip_date DATE NOT NULL,
        departure_time TIMESTAMP NOT NULL,
        arrival_eta TIMESTAMP,
        status VARCHAR(20) NOT NULL DEFAULT 'SCHEDULED' CHECK (
            status IN (
                'SCHEDULED',
                'BOARDING',
                'DEPARTED',
                'ARRIVED',
                'CANCELLED'
            )
        ),
        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
    );

-- Fare rules table
CREATE TABLE
    fare_rules (
        id BIGSERIAL PRIMARY KEY,
        route_id BIGINT NOT NULL REFERENCES routes (id),
        from_stop_id BIGINT NOT NULL REFERENCES stops (id),
        to_stop_id BIGINT NOT NULL REFERENCES stops (id),
        base_price DECIMAL(10, 2) NOT NULL,
        discounts JSONB,
        dynamic_pricing_enabled BOOLEAN NOT NULL DEFAULT FALSE
    );

-- Seat holds table
CREATE TABLE
    seat_holds (
        id BIGSERIAL PRIMARY KEY,
        trip_id BIGINT NOT NULL REFERENCES trips (id),
        seat_number INTEGER NOT NULL,
        user_id BIGINT NOT NULL REFERENCES users (id),
        expires_at TIMESTAMP NOT NULL,
        status VARCHAR(20) NOT NULL DEFAULT 'HOLD' CHECK (status IN ('HOLD', 'EXPIRED')),
        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
    );

-- Tickets table
CREATE TABLE
    tickets (
        id BIGSERIAL PRIMARY KEY,
        trip_id BIGINT NOT NULL REFERENCES trips (id),
        passenger_id BIGINT NOT NULL REFERENCES users (id),
        seat_number INTEGER NOT NULL,
        from_stop_id BIGINT NOT NULL REFERENCES stops (id),
        to_stop_id BIGINT NOT NULL REFERENCES stops (id),
        price DECIMAL(10, 2) NOT NULL,
        payment_method VARCHAR(20) NOT NULL CHECK (
            payment_method IN ('CASH', 'TRANSFER', 'QR', 'CARD')
        ),
        status VARCHAR(20) NOT NULL DEFAULT 'SOLD' CHECK (status IN ('SOLD', 'CANCELLED', 'NO_SHOW')),
        qr_code VARCHAR(255) UNIQUE,
        purchased_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
    );

-- Baggage table
CREATE TABLE
    baggage (
        id BIGSERIAL PRIMARY KEY,
        ticket_id BIGINT NOT NULL UNIQUE REFERENCES tickets (id),
        weight_kg DECIMAL(5, 2) NOT NULL,
        excess_fee DECIMAL(10, 2) DEFAULT 0.00,
        tag_code VARCHAR(50) UNIQUE,
        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
    );

-- Parcels table
CREATE TABLE
    parcels (
        id BIGSERIAL PRIMARY KEY,
        code VARCHAR(50) NOT NULL UNIQUE,
        trip_id BIGINT NOT NULL REFERENCES trips (id),
        sender_name VARCHAR(100) NOT NULL,
        sender_phone VARCHAR(20) NOT NULL,
        receiver_name VARCHAR(100) NOT NULL,
        receiver_phone VARCHAR(20) NOT NULL,
        from_stop_id BIGINT NOT NULL REFERENCES stops (id),
        to_stop_id BIGINT NOT NULL REFERENCES stops (id),
        price DECIMAL(10, 2) NOT NULL,
        weight_kg DECIMAL(6, 2),
        status VARCHAR(20) NOT NULL DEFAULT 'CREATED' CHECK (
            status IN ('CREATED', 'IN_TRANSIT', 'DELIVERED', 'FAILED')
        ),
        delivery_otp VARCHAR(6),
        proof_photo_url VARCHAR(255),
        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
        delivered_at TIMESTAMP
    );

-- Assignments table
CREATE TABLE
    assignments (
        id BIGSERIAL PRIMARY KEY,
        trip_id BIGINT NOT NULL UNIQUE REFERENCES trips (id),
        driver_id BIGINT NOT NULL REFERENCES users (id),
        dispatcher_id BIGINT REFERENCES users (id),
        checklist_ok BOOLEAN NOT NULL DEFAULT FALSE,
        soat_valid BOOLEAN NOT NULL DEFAULT FALSE,
        revision_valid BOOLEAN NOT NULL DEFAULT FALSE,
        assigned_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
    );

-- Incidents table
CREATE TABLE
    incidents (
        id BIGSERIAL PRIMARY KEY,
        entity_type VARCHAR(20) NOT NULL CHECK (entity_type IN ('TRIP', 'TICKET', 'PARCEL')),
        entity_id BIGINT NOT NULL,
        incident_type VARCHAR(30) NOT NULL CHECK (
            incident_type IN (
                'SECURITY',
                'DELIVERY_FAIL',
                'OVERBOOK',
                'VEHICLE'
            )
        ),
        description TEXT,
        reported_by BIGINT REFERENCES users (id),
        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
    );

-- Tabla de configuración dinámica del sistema
CREATE TABLE
    config (
        id BIGSERIAL PRIMARY KEY,
        config_key VARCHAR(100) UNIQUE NOT NULL,
        config_value VARCHAR(255) NOT NULL,
        description VARCHAR(500),
        data_type VARCHAR(20) NOT NULL,
        updated_at TIMESTAMP,
        updated_by BIGINT,
        CONSTRAINT fk_config_user FOREIGN KEY (updated_by) REFERENCES users (id) ON DELETE SET NULL
    );