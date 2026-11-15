CREATE TABLE outbox_events (
    id UUID NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    retry_count INT NOT NULL DEFAULT 0,
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    sent_at TIMESTAMPTZ,
    version BIGINT NOT NULL,
    CONSTRAINT pk_outbox_events PRIMARY KEY (id),
    CONSTRAINT chk_outbox_events_status CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    CONSTRAINT chk_outbox_events_retry_count CHECK (retry_count >= 0),
    CONSTRAINT chk_outbox_events_aggregate_type CHECK (length(trim(aggregate_type)) > 0),
    CONSTRAINT chk_outbox_events_event_type CHECK (length(trim(event_type)) > 0),
    CONSTRAINT chk_outbox_events_payload CHECK (length(trim(payload)) > 0)
);

CREATE INDEX idx_outbox_events_pending_created ON outbox_events (created_at ASC) WHERE status = 'PENDING';
CREATE INDEX idx_outbox_events_aggregate ON outbox_events (aggregate_type, aggregate_id);
