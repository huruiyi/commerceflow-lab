CREATE INDEX idx_messaging_recent_failure ON messaging_deliveries (channel, status, finished_at);
