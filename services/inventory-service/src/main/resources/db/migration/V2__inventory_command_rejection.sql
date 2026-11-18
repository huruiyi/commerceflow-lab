ALTER TABLE inventory_command_inbox
  DROP CHECK chk_inventory_command_status,
  ADD COLUMN rejection_message VARCHAR(300) NULL,
  ADD CONSTRAINT chk_inventory_command_status
    CHECK (
      (status IN ('PROCESSING','SUCCEEDED') AND rejection_message IS NULL)
      OR (status='REJECTED' AND command_type='RESERVE' AND rejection_message IS NOT NULL)
    );
