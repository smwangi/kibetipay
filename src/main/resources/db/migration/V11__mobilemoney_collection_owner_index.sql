-- Customer top-up history (GET /api/mobile-money/collections): newest first per owner, keyset paged.
CREATE INDEX ix_mobilemoney_collection_owner_time ON mobilemoney_collection (owner_id, created_at DESC, id DESC);
