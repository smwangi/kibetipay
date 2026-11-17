-- Existing loans retain their current status; historic borrower consent is not inferred.
ALTER TABLE lending_loan ADD COLUMN interest_bps_snapshot INT;
ALTER TABLE lending_loan ADD COLUMN fee_bps_snapshot INT;
ALTER TABLE lending_loan ADD COLUMN tenor_days_snapshot INT;
ALTER TABLE lending_loan ADD COLUMN installments_snapshot INT;
ALTER TABLE lending_loan ADD COLUMN penalty_bps_per_day_snapshot INT;
ALTER TABLE lending_loan ADD COLUMN borrower_decision VARCHAR(8);
ALTER TABLE lending_loan ADD COLUMN borrower_decided_at TIMESTAMPTZ;

UPDATE lending_loan l SET interest_bps_snapshot = p.interest_bps, fee_bps_snapshot = p.fee_bps,
    tenor_days_snapshot = p.tenor_days, installments_snapshot = p.installments,
    penalty_bps_per_day_snapshot = p.penalty_bps_per_day
FROM lending_product p WHERE p.code = l.product_code;

ALTER TABLE lending_loan ALTER COLUMN interest_bps_snapshot SET NOT NULL;
ALTER TABLE lending_loan ALTER COLUMN fee_bps_snapshot SET NOT NULL;
ALTER TABLE lending_loan ALTER COLUMN tenor_days_snapshot SET NOT NULL;
ALTER TABLE lending_loan ALTER COLUMN installments_snapshot SET NOT NULL;
ALTER TABLE lending_loan ALTER COLUMN penalty_bps_per_day_snapshot SET NOT NULL;
ALTER TABLE lending_loan ADD CONSTRAINT ck_loan_borrower_decision
    CHECK ((borrower_decision IS NULL AND borrower_decided_at IS NULL) OR
           (borrower_decision IS NOT NULL AND borrower_decision IN ('ACCEPT', 'DECLINE') AND borrower_decided_at IS NOT NULL));

DROP INDEX ux_loan_one_open_per_borrower;
CREATE UNIQUE INDEX ux_loan_one_open_per_borrower ON lending_loan (borrower_id)
    WHERE status IN ('OFFERED', 'APPLIED', 'IN_REVIEW', 'ACTIVE', 'DEFAULTED');
