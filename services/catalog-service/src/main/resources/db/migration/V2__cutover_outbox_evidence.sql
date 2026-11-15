ALTER TABLE catalog_cutover_state
  ADD COLUMN outbox_count INT NOT NULL DEFAULT 0 AFTER translation_count,
  ADD COLUMN outbox_fingerprint BIGINT UNSIGNED NOT NULL DEFAULT 0
    AFTER translation_fingerprint;
