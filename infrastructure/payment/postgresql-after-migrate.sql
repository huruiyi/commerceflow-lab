GRANT SELECT ON payment_merchant_accounts, payment_records, refund_records, payment_events, refund_events,
  payment_notifications, refund_notifications, payment_outbox, payment_command_outbox, payment_process_managers,
  payment_idempotency_locks, payment_remote_message_inbox, payment_result_inbox, payment_report_order_ownership,
  payment_shadow_orphan_quarantine, payment_reconciliation_issue
  TO payment_orchestration_runtime;

GRANT INSERT ON payment_events, refund_events, payment_notifications, refund_notifications,
  payment_idempotency_locks, payment_result_inbox TO payment_orchestration_runtime;
GRANT UPDATE (created_at) ON payment_idempotency_locks TO payment_orchestration_runtime;
GRANT INSERT, UPDATE (status, failure_reason, paid_at, updated_at, version, refunded_amount)
  ON payment_records TO payment_orchestration_runtime;
GRANT INSERT, UPDATE (status, failure_reason, channel_refund_no, succeeded_at, failed_at, updated_at, version,
  inventory_status, order_status_before_refund) ON refund_records TO payment_orchestration_runtime;
GRANT INSERT, UPDATE (status, attempts, next_attempt_at, last_error, published_at)
  ON payment_outbox TO payment_orchestration_runtime;
GRANT INSERT, UPDATE (status, attempts, next_attempt_at, last_error, acknowledged_at)
  ON payment_command_outbox TO payment_orchestration_runtime;
GRANT INSERT, UPDATE (status, result_deadline_at, next_check_at, last_error, completed_at, updated_at,
  recovered_by, recovery_reason, recovered_at, version) ON payment_process_managers TO payment_orchestration_runtime;
GRANT INSERT, UPDATE (processing_status, response_payload, error_detail, processed_at)
  ON payment_remote_message_inbox TO payment_orchestration_runtime;
GRANT INSERT, UPDATE (status, merchant_id) ON payment_report_order_ownership TO payment_orchestration_runtime;

GRANT INSERT, SELECT (expires_at), DELETE ON payment_internal_request_replay TO payment_orchestration_runtime;
ALTER TABLE payment_internal_request_replay ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS payment_runtime_replay_insert ON payment_internal_request_replay;
DROP POLICY IF EXISTS payment_runtime_replay_expired ON payment_internal_request_replay;
CREATE POLICY payment_runtime_replay_insert ON payment_internal_request_replay FOR INSERT
  TO payment_orchestration_runtime WITH CHECK (true);
CREATE POLICY payment_runtime_replay_expired ON payment_internal_request_replay FOR ALL
  TO payment_orchestration_runtime USING (expires_at < CURRENT_TIMESTAMP(6));

GRANT USAGE ON SEQUENCE payment_records_id_seq, refund_records_id_seq, payment_events_id_seq, refund_events_id_seq,
  payment_notifications_id_seq, refund_notifications_id_seq, payment_outbox_id_seq TO payment_orchestration_runtime;
