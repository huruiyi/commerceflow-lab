ALTER TABLE warehouse_returns
  ADD COLUMN supplement_id CHAR(36) NULL,
  ADD COLUMN qualified_quantity INT NULL,
  ADD COLUMN supplement_note VARCHAR(300) NULL,
  ADD COLUMN supplement_at TIMESTAMP(6) NULL,
  ADD CONSTRAINT ck_return_supplement CHECK (
    (supplement_id IS NULL AND qualified_quantity IS NULL AND supplement_note IS NULL AND supplement_at IS NULL)
    OR (supplement_id IS NOT NULL AND qualified_quantity IS NOT NULL AND received_quantity IS NOT NULL
      AND qualified_quantity BETWEEN 1 AND received_quantity AND supplement_note IS NOT NULL
      AND supplement_at IS NOT NULL AND status IN ('SHORT_RECEIVED','FAILED'))
  );
