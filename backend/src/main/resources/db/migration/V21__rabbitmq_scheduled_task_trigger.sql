ALTER TABLE scheduled_tasks
  ADD COLUMN trigger_published_at TIMESTAMP(6) NULL AFTER last_trigger_source,
  ADD COLUMN trigger_publish_attempts INT NOT NULL DEFAULT 0 AFTER trigger_published_at,
  ADD COLUMN trigger_last_error VARCHAR(1000) NULL AFTER trigger_publish_attempts;

CREATE INDEX idx_scheduled_tasks_trigger_publish
  ON scheduled_tasks(primary_trigger_snapshot,status,trigger_published_at,due_at,id);
