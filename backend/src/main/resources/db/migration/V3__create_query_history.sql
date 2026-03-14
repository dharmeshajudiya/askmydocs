CREATE TABLE query_history (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    document_id   UUID        NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    question      TEXT        NOT NULL,
    answer        TEXT        NOT NULL,
    source_chunks JSONB       NOT NULL DEFAULT '[]',
    latency_ms    INTEGER,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_query_history_user_id ON query_history(user_id);
