CREATE INDEX idx_invoice_monitor_accepted ON invoice_requests (kind, status, accepted_at);
