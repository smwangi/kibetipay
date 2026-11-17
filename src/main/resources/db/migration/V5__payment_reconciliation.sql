-- Imported provider evidence is kept separate from expected platform payments.
-- Matching these records never posts money or changes a wallet balance.
CREATE TABLE mobilemoney_expected_payment (
    id UUID PRIMARY KEY,
    provider VARCHAR(40) NOT NULL,
    reference VARCHAR(100) NOT NULL,
    direction VARCHAR(10) NOT NULL CHECK (direction IN ('COLLECTION', 'PAYOUT')),
    amount_minor BIGINT NOT NULL CHECK (amount_minor > 0),
    currency CHAR(3) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by VARCHAR(64) NOT NULL,
    UNIQUE (provider, reference)
);
CREATE TABLE mobilemoney_provider_transaction (
    id UUID PRIMARY KEY,
    provider VARCHAR(40) NOT NULL,
    provider_reference VARCHAR(100) NOT NULL,
    expected_reference VARCHAR(100),
    direction VARCHAR(10) NOT NULL CHECK (direction IN ('COLLECTION', 'PAYOUT')),
    amount_minor BIGINT NOT NULL CHECK (amount_minor > 0),
    currency CHAR(3) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    imported_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    imported_by VARCHAR(64) NOT NULL,
    UNIQUE (provider, provider_reference)
);
CREATE INDEX ix_mobilemoney_provider_match ON mobilemoney_provider_transaction (provider, expected_reference);
