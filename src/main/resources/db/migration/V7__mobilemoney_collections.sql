CREATE TABLE mobilemoney_collection (
    id UUID PRIMARY KEY,
    owner_id VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(64) NOT NULL,
    provider VARCHAR(40) NOT NULL,
    provider_request_id VARCHAR(100),
    msisdn VARCHAR(12) NOT NULL,
    amount_minor BIGINT NOT NULL CHECK (amount_minor > 0),
    currency CHAR(3) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('SUBMITTING', 'PENDING', 'UNKNOWN', 'REJECTED', 'COMPLETED', 'FAILED')),
    provider_receipt VARCHAR(100),
    journal_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (owner_id, idempotency_key),
    UNIQUE (provider, provider_request_id),
    UNIQUE (provider, provider_receipt)
);
CREATE INDEX ix_mobilemoney_collection_status ON mobilemoney_collection (status, created_at);
