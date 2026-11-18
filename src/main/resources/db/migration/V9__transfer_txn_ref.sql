-- Customer-facing transaction reference (e.g. KP7H3QX9M2AB), separate from the UUID primary key.
-- New rows get theirs from Ids.newTxnRef(); this backfills existing rows deterministically from
-- their id using the same KP + 10 Crockford Base32 format.
ALTER TABLE transfer ADD COLUMN txn_ref VARCHAR(16);

UPDATE transfer t
SET txn_ref = 'KP' || (
    SELECT string_agg(substr('0123456789ABCDEFGHJKMNPQRSTVWXYZ',
                             get_byte(decode(md5(t.id::text), 'hex'), i) % 32 + 1, 1), '' ORDER BY i)
    FROM generate_series(0, 9) AS i);

ALTER TABLE transfer ALTER COLUMN txn_ref SET NOT NULL;
ALTER TABLE transfer ADD CONSTRAINT ux_transfer_txn_ref UNIQUE (txn_ref);
