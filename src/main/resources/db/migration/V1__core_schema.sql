-- Each module owns its tables (prefix = module). No cross-module foreign keys,
-- so any module can later be extracted into its own service + database.

-- ===================== wallet module =====================
CREATE TABLE wallet_account (
    id              UUID        PRIMARY KEY,
    owner_id        VARCHAR(64) NOT NULL,
    currency        CHAR(3)     NOT NULL,
    type            VARCHAR(16) NOT NULL,              -- USER | SYSTEM
    status          VARCHAR(16) NOT NULL,              -- ACTIVE | FROZEN
    balance_minor   BIGINT      NOT NULL DEFAULT 0,
    allow_negative  BOOLEAN     NOT NULL DEFAULT FALSE,     -- only float/expense style system accounts
    version         BIGINT      NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL,
    -- Invariant enforced by the DB as a last line of defence:
    CONSTRAINT ck_wallet_no_overdraft CHECK (allow_negative OR balance_minor >= 0)
);
CREATE UNIQUE INDEX ux_wallet_owner_currency ON wallet_account (owner_id, currency) WHERE type = 'USER';

CREATE TABLE wallet_journal (
    id          UUID         PRIMARY KEY,
    reference   VARCHAR(100) NOT NULL UNIQUE,          -- idempotency at the ledger level
    kind        VARCHAR(24)  NOT NULL,                 -- DEPOSIT | TRANSFER | LOAN_DISBURSEMENT | ...
    created_at  TIMESTAMPTZ  NOT NULL
);

-- Double-entry: every journal has >= 2 entries and SUM(debits) = SUM(credits).
CREATE TABLE wallet_ledger_entry (
    id             UUID        PRIMARY KEY,
    journal_id     UUID        NOT NULL REFERENCES wallet_journal (id),
    wallet_id      UUID        NOT NULL REFERENCES wallet_account (id),
    direction      VARCHAR(6)  NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    amount_minor   BIGINT      NOT NULL CHECK (amount_minor > 0),
    currency       CHAR(3)     NOT NULL,
    balance_after  BIGINT      NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_ledger_wallet_time ON wallet_ledger_entry (wallet_id, created_at DESC);
CREATE INDEX ix_ledger_journal ON wallet_ledger_entry (journal_id);

-- ===================== transfer module =====================
CREATE TABLE transfer (
    id                     UUID         PRIMARY KEY,
    initiator_id           VARCHAR(64)  NOT NULL,
    recipient_id           VARCHAR(64)  NOT NULL,
    amount_minor           BIGINT       NOT NULL CHECK (amount_minor > 0),
    currency               CHAR(3)      NOT NULL,
    note                   VARCHAR(140),
    status                 VARCHAR(20)  NOT NULL,      -- PENDING_RISK | COMPLETED | REJECTED | FAILED | IN_REVIEW
    idempotency_key        VARCHAR(64)  NOT NULL,
    risk_score             INT,
    failure_reason         VARCHAR(255),
    journal_id             UUID,
    version                BIGINT       NOT NULL DEFAULT 0,
    created_at             TIMESTAMPTZ  NOT NULL,
    updated_at             TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ux_transfer_idempotency UNIQUE (initiator_id, idempotency_key)
);
CREATE INDEX ix_transfer_initiator_time ON transfer (initiator_id, created_at DESC);
CREATE INDEX ix_transfer_recipient_time ON transfer (recipient_id, created_at DESC);

-- ===================== risk module =====================
CREATE TABLE risk_assessment (
    id           UUID         PRIMARY KEY,
    transfer_id  UUID         NOT NULL UNIQUE,
    subject_id   VARCHAR(64)  NOT NULL,
    score        INT          NOT NULL,
    decision     VARCHAR(10)  NOT NULL,                -- APPROVE | REVIEW | REJECT
    reasons      TEXT         NOT NULL,
    explanation  TEXT,
    created_at   TIMESTAMPTZ  NOT NULL
);

-- ===================== anomaly module =====================
CREATE TABLE anomaly_daily_outflow (
    owner_id      VARCHAR(64) NOT NULL,
    day           DATE        NOT NULL,
    outflow_minor BIGINT      NOT NULL DEFAULT 0,
    tx_count      INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (owner_id, day)
);

-- Inbox / dedupe table: makes the listener idempotent under at-least-once delivery.
CREATE TABLE anomaly_processed_transfer (
    transfer_id   UUID        PRIMARY KEY,
    processed_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE anomaly_alert (
    id            UUID          PRIMARY KEY,
    owner_id      VARCHAR(64)   NOT NULL,
    transfer_id   UUID          NOT NULL,
    kind          VARCHAR(32)   NOT NULL,
    z_score       NUMERIC(8, 3),
    details       TEXT          NOT NULL,
    acknowledged  BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ   NOT NULL
);
CREATE INDEX ix_anomaly_alert_owner ON anomaly_alert (owner_id, created_at DESC);

-- ===================== insights module (read model) =====================
CREATE TABLE insight_transaction (
    transfer_id   UUID         NOT NULL,
    owner_id      VARCHAR(64)  NOT NULL,
    direction     VARCHAR(3)   NOT NULL,               -- IN | OUT
    counterparty  VARCHAR(64)  NOT NULL,
    amount_minor  BIGINT       NOT NULL,
    currency      CHAR(3)      NOT NULL,
    note          VARCHAR(140),
    category      VARCHAR(32),                         -- filled asynchronously by the LLM
    occurred_at   TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (transfer_id, owner_id)
);
CREATE INDEX ix_insight_owner_time ON insight_transaction (owner_id, occurred_at DESC);
