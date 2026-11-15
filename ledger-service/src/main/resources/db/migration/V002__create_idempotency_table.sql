CREATE TABLE idempotency_keys (
    id UUID NOT NULL,
    user_id UUID NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    endpoint VARCHAR(100) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL,
    response_status INT,
    response_body TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL,
    CONSTRAINT pk_idempotency_keys PRIMARY KEY (id),
    CONSTRAINT uq_idempotency_keys_user_key UNIQUE (user_id, idempotency_key),
    CONSTRAINT chk_idempotency_keys_status CHECK (status IN ('PROCESSING', 'COMPLETED', 'FAILED')),
    CONSTRAINT chk_idempotency_keys_key_format CHECK (idempotency_key ~ '^[A-Za-z0-9_.:-]{1,128}$'),
    CONSTRAINT chk_idempotency_keys_request_hash CHECK (request_hash ~ '^[a-f0-9]{64}$'),
    CONSTRAINT chk_idempotency_keys_response_status CHECK (response_status IS NULL OR (response_status >= 100 AND response_status <= 599))
);

CREATE INDEX idx_idempotency_keys_expires_at ON idempotency_keys (expires_at);
