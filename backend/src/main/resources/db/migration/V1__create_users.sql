CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE TABLE users (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    email         VARCHAR(255) NOT NULL UNIQUE,
    hashed_password TEXT       NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
