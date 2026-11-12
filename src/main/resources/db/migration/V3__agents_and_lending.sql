-- ===================== wallet: platform accounts for lending =====================
-- The lending pool cannot go negative: loans can only be disbursed from money that was put in.
INSERT INTO wallet_account (id, owner_id, currency, type, status, balance_minor, allow_negative, version, created_at) VALUES
  ('00000000-0000-0000-0000-000000000010', 'system:lending-pool',       'KES', 'SYSTEM', 'ACTIVE', 0, FALSE, 0, now()),
  ('00000000-0000-0000-0000-000000000011', 'system:interest-income',    'KES', 'SYSTEM', 'ACTIVE', 0, FALSE, 0, now()),
  ('00000000-0000-0000-0000-000000000012', 'system:fee-income',         'KES', 'SYSTEM', 'ACTIVE', 0, FALSE, 0, now()),
  ('00000000-0000-0000-0000-000000000013', 'system:penalty-income',     'KES', 'SYSTEM', 'ACTIVE', 0, FALSE, 0, now()),
  ('00000000-0000-0000-0000-000000000014', 'system:commission-expense', 'KES', 'SYSTEM', 'ACTIVE', 0, TRUE,  0, now());

-- ===================== agent module =====================
-- Adjacency list (parent_id). Subtree queries use WITH RECURSIVE; see AgentDirectory.
CREATE TABLE agent (
    id                   UUID         PRIMARY KEY,
    user_id              VARCHAR(64)  NOT NULL UNIQUE,
    parent_id            UUID         REFERENCES agent (id),
    code                 VARCHAR(16)  NOT NULL UNIQUE,
    display_name         VARCHAR(100) NOT NULL,
    region               VARCHAR(64)  NOT NULL,
    depth                INT          NOT NULL CHECK (depth >= 1),
    status               VARCHAR(16)  NOT NULL,          -- ACTIVE | SUSPENDED
    lending_limit_minor  BIGINT       NOT NULL CHECK (lending_limit_minor >= 0),
    created_at           TIMESTAMPTZ  NOT NULL
);
CREATE INDEX ix_agent_parent ON agent (parent_id);

-- ===================== lending module =====================
CREATE TABLE lending_product (
    code                    VARCHAR(32)  PRIMARY KEY,
    name                    VARCHAR(100) NOT NULL,
    min_principal_minor     BIGINT       NOT NULL,
    max_principal_minor     BIGINT       NOT NULL,
    first_loan_max_minor    BIGINT       NOT NULL,       -- "loan ladder": new borrowers start small
    interest_bps            INT          NOT NULL,       -- flat, for the whole tenor
    fee_bps                 INT          NOT NULL,
    tenor_days              INT          NOT NULL,
    installments            INT          NOT NULL CHECK (installments >= 1),
    penalty_bps_per_day     INT          NOT NULL,
    active                  BOOLEAN      NOT NULL DEFAULT TRUE
);

INSERT INTO lending_product VALUES
  ('SOKO-28',     'Soko 4-week loan',       50000,  5000000,  500000, 1000, 300, 28, 4, 10, TRUE),
  ('BIASHARA-90', 'Biashara 3-month loan', 500000, 20000000, 2000000, 2400, 300, 90, 3,  5, TRUE);

CREATE TABLE lending_loan (
    id                        UUID         PRIMARY KEY,
    borrower_id               VARCHAR(64)  NOT NULL,
    agent_id                  UUID         NOT NULL,      -- no FK: agent lives in another module
    agent_user_id             VARCHAR(64)  NOT NULL,
    product_code              VARCHAR(32)  NOT NULL REFERENCES lending_product (code),
    principal_minor           BIGINT       NOT NULL CHECK (principal_minor > 0),
    interest_minor            BIGINT       NOT NULL,
    fee_minor                 BIGINT       NOT NULL,
    currency                  CHAR(3)      NOT NULL,
    status                    VARCHAR(16)  NOT NULL,      -- APPLIED | IN_REVIEW | DECLINED | ACTIVE | REPAID | DEFAULTED
    credit_score              INT,
    decision_reasons          TEXT,
    idempotency_key           VARCHAR(64)  NOT NULL,
    disbursement_journal_id   UUID,
    applied_at                TIMESTAMPTZ  NOT NULL,
    disbursed_at              TIMESTAMPTZ,
    closed_at                 TIMESTAMPTZ,
    version                   BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ux_loan_idempotency UNIQUE (agent_id, idempotency_key)
);
CREATE INDEX ix_loan_borrower ON lending_loan (borrower_id, applied_at DESC);
CREATE INDEX ix_loan_agent_status ON lending_loan (agent_id, status);
-- At most one open loan per borrower, enforced by the database, not just by code:
CREATE UNIQUE INDEX ux_loan_one_open_per_borrower ON lending_loan (borrower_id)
    WHERE status IN ('APPLIED', 'IN_REVIEW', 'ACTIVE');

CREATE TABLE lending_installment (
    id               UUID        PRIMARY KEY,
    loan_id          UUID        NOT NULL REFERENCES lending_loan (id),
    seq              INT         NOT NULL,
    due_date         DATE        NOT NULL,
    principal_due    BIGINT      NOT NULL,
    interest_due     BIGINT      NOT NULL,
    fee_due          BIGINT      NOT NULL,
    penalty_due      BIGINT      NOT NULL DEFAULT 0,
    principal_paid   BIGINT      NOT NULL DEFAULT 0,
    interest_paid    BIGINT      NOT NULL DEFAULT 0,
    fee_paid         BIGINT      NOT NULL DEFAULT 0,
    penalty_paid     BIGINT      NOT NULL DEFAULT 0,
    status           VARCHAR(10) NOT NULL,               -- DUE | OVERDUE | PAID
    CONSTRAINT ux_installment UNIQUE (loan_id, seq)
);
CREATE INDEX ix_installment_status_due ON lending_installment (status, due_date);

CREATE TABLE lending_repayment (
    id               UUID         PRIMARY KEY,
    loan_id          UUID         NOT NULL REFERENCES lending_loan (id),
    reference        VARCHAR(100) NOT NULL UNIQUE,
    payer_id         VARCHAR(64)  NOT NULL,
    channel          VARCHAR(16)  NOT NULL,              -- WALLET | AGENT_CASH
    amount_minor     BIGINT       NOT NULL,
    penalty_minor    BIGINT       NOT NULL,
    fee_minor        BIGINT       NOT NULL,
    interest_minor   BIGINT       NOT NULL,
    principal_minor  BIGINT       NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL
);
CREATE INDEX ix_repayment_loan ON lending_repayment (loan_id, created_at);

CREATE TABLE lending_commission (
    id             UUID        PRIMARY KEY,
    repayment_id   UUID        NOT NULL REFERENCES lending_repayment (id),
    agent_id       UUID        NOT NULL,
    agent_user_id  VARCHAR(64) NOT NULL,
    level          INT         NOT NULL,                 -- 0 = originating agent, 1 = its parent, ...
    amount_minor   BIGINT      NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL
);

-- Run-once guard for daily batch jobs: safe with several app instances and with restarts.
CREATE TABLE lending_job_run (
    job_name    VARCHAR(64) NOT NULL,
    run_date    DATE        NOT NULL,
    started_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (job_name, run_date)
);
