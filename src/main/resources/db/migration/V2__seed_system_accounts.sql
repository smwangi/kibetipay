-- Float account that funds deposits (simulates the M-Pesa / bank settlement account).
-- SYSTEM accounts may go negative: their balance mirrors money that entered the platform.
INSERT INTO wallet_account (id, owner_id, currency, type, status, balance_minor, allow_negative, version, created_at)
VALUES ('00000000-0000-0000-0000-000000000001', 'system:float', 'KES', 'SYSTEM', 'ACTIVE', 0, TRUE, 0, now());
