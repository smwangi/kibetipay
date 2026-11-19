-- Suspense items: provider collections that reached the platform float but cannot yet be
-- attributed to a customer. The system:suspense wallet itself is created per operating currency
-- by PlatformCurrencyInitializer, like the other system wallets.
--
-- OPEN      : booked Dr system:float / Cr system:suspense (suspense_journal_id)
-- ALLOCATED : cleared Dr system:suspense / Cr customer wallet (allocation_journal_id)
CREATE TABLE mobilemoney_suspense_item (
    id                     UUID         PRIMARY KEY,
    provider_transaction_id UUID        NOT NULL UNIQUE REFERENCES mobilemoney_provider_transaction (id),
    amount_minor           BIGINT       NOT NULL CHECK (amount_minor > 0),
    currency               CHAR(3)      NOT NULL,
    status                 VARCHAR(16)  NOT NULL CHECK (status IN ('OPEN', 'ALLOCATED')),
    reason                 VARCHAR(500) NOT NULL,
    suspense_journal_id    UUID         NOT NULL,
    opened_at              TIMESTAMPTZ  NOT NULL,
    opened_by              VARCHAR(64)  NOT NULL,
    allocated_to           VARCHAR(64),
    allocation_note        VARCHAR(500),
    allocation_journal_id  UUID,
    allocated_at           TIMESTAMPTZ,
    allocated_by           VARCHAR(64),
    CONSTRAINT ck_suspense_allocation CHECK (
        (status = 'OPEN' AND allocated_to IS NULL AND allocation_journal_id IS NULL AND allocated_at IS NULL)
        OR (status = 'ALLOCATED' AND allocated_to IS NOT NULL AND allocation_note IS NOT NULL
            AND allocation_journal_id IS NOT NULL AND allocated_at IS NOT NULL AND allocated_by IS NOT NULL))
);
CREATE INDEX ix_mobilemoney_suspense_status ON mobilemoney_suspense_item (status, opened_at);
