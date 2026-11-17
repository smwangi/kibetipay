CREATE TABLE notification_message (
    id UUID PRIMARY KEY,
    recipient_id VARCHAR(64) NOT NULL,
    event_key VARCHAR(160) NOT NULL UNIQUE,
    kind VARCHAR(32) NOT NULL,
    message TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    read_at TIMESTAMPTZ
);
CREATE INDEX ix_notification_recipient ON notification_message (recipient_id, created_at DESC);

-- A defaulted loan remains open until it is fully repaid.
DROP INDEX ux_loan_one_open_per_borrower;
CREATE UNIQUE INDEX ux_loan_one_open_per_borrower ON lending_loan (borrower_id)
    WHERE status IN ('APPLIED', 'IN_REVIEW', 'ACTIVE', 'DEFAULTED');
