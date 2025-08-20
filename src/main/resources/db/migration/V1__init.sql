CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "btree_gist";

-- users
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    first_name VARCHAR(100),
    last_name VARCHAR(100),
    phone VARCHAR(50),
    email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL
);

CREATE TABLE verification_tokens (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    token VARCHAR(255) NOT NULL UNIQUE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    expires_at TIMESTAMP WITHOUT TIME ZONE NOT NULL
);

-- inventory
CREATE TABLE car_types (
    id VARCHAR(20) PRIMARY KEY,
    display_name VARCHAR(100) NOT NULL,
    description TEXT,
    price_per_day NUMERIC(10,2) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    total_quantity INT NOT NULL CHECK (total_quantity >= 0),
    photo_url TEXT,
    metadata JSONB
);

-- bookings
CREATE TABLE bookings (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES users(id),
    type_id VARCHAR(20) NOT NULL REFERENCES car_types(id),
    status VARCHAR(20) NOT NULL,
    time_range TSRANGE NOT NULL,
    start_ts TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    end_ts TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    days INT NOT NULL,
    price_per_day NUMERIC(10,2) NOT NULL,
    total NUMERIC(10,2) NOT NULL,
    license_key TEXT,
    car_registration_number VARCHAR(50),
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL
);

CREATE INDEX bookings_time_range_idx ON bookings USING GIST (time_range);
CREATE INDEX bookings_type_status_idx ON bookings (type_id, status);

-- idempotency
CREATE TABLE idempotency_keys (
    key VARCHAR(100) PRIMARY KEY,
    payload_hash VARCHAR(128) NOT NULL,
    booking_id UUID,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL
);

-- seed car types
INSERT INTO car_types (id, display_name, description, price_per_day, currency, total_quantity, photo_url, metadata)
VALUES
('SEDAN','Sedan','Comfortable sedan', 50.00, 'USD', 10, 'https://example.com/sedan.jpg', '{"seats":5,"transmission":"AUTO"}'::jsonb),
('SUV','SUV','Spacious SUV', 80.00, 'USD', 6, 'https://example.com/suv.jpg', '{"seats":7,"transmission":"AUTO"}'::jsonb),
('VAN','Van','Large van', 100.00, 'USD', 4, 'https://example.com/van.jpg', '{"seats":9,"transmission":"MANUAL"}'::jsonb);

