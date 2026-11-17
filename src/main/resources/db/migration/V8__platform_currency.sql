-- One configured operating currency per deployment; changing an established book is not FX.
CREATE TABLE wallet_platform_currency (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    currency CHAR(3) NOT NULL
);

-- Preserve the operating currency of an existing book; an empty seeded system wallet does not
-- determine it. Include pending records that may not yet have posted to the ledger.
DO $$
DECLARE
    observed_count INT;
    observed_currency CHAR(3);
BEGIN
    SELECT count(DISTINCT currency), min(currency) INTO observed_count, observed_currency
    FROM (
        SELECT currency FROM wallet_account WHERE type = 'USER'
        UNION ALL SELECT currency FROM wallet_ledger_entry
        UNION ALL SELECT currency FROM transfer
        UNION ALL SELECT currency FROM lending_loan
        UNION ALL SELECT currency FROM insight_transaction
        UNION ALL SELECT currency FROM mobilemoney_collection
        UNION ALL SELECT currency FROM mobilemoney_expected_payment
        UNION ALL SELECT currency FROM mobilemoney_provider_transaction
    ) existing;
    IF observed_count > 1 THEN
        RAISE EXCEPTION 'Existing financial records contain multiple currencies; manual migration is required';
    END IF;
    IF observed_count = 1 THEN
        INSERT INTO wallet_platform_currency (singleton, currency) VALUES (true, observed_currency);
    END IF;
END $$;

CREATE UNIQUE INDEX ux_wallet_system_owner_currency
    ON wallet_account (owner_id, currency) WHERE type = 'SYSTEM';

ALTER TABLE mobilemoney_collection ALTER COLUMN msisdn TYPE VARCHAR(15);
