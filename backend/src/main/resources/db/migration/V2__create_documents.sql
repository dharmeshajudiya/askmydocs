CREATE TABLE documents (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    filename      VARCHAR(255) NOT NULL,
    s3_url        TEXT         NOT NULL,
    status        VARCHAR(20)  NOT NULL DEFAULT 'queued',
    total_chunks  INTEGER,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    error_log     TEXT
);

CREATE INDEX idx_documents_user_id ON documents(user_id);
