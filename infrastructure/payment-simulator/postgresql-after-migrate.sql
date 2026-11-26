GRANT SELECT ON
  payment_admin_user, payment_admin_role, payment_admin_user_role, payment_admin_user_merchant,
  payment_admin_user_merchant_role, payment_admin_role_permission, payment_admin_mfa_credential,
  payment_admin_mfa_recovery_code, payment_audit_chain_head, payment_admin_audit_log,
  payment_platform_key, merchant_callback_endpoint, channel_transaction, channel_refund,
  channel_payouts
  TO payment_simulator_runtime;

GRANT INSERT, UPDATE ON payment_admin_user, payment_admin_mfa_credential, payment_merchant
  TO payment_simulator_runtime;
GRANT SELECT ON payment_merchant TO payment_simulator_runtime;
GRANT INSERT, UPDATE, DELETE ON payment_admin_role, payment_admin_user_role, payment_admin_user_merchant_role
  TO payment_simulator_runtime;
GRANT INSERT, DELETE ON payment_admin_role_permission, payment_admin_mfa_recovery_code
  TO payment_simulator_runtime;
GRANT UPDATE (used_at) ON payment_admin_mfa_recovery_code TO payment_simulator_runtime;
GRANT INSERT ON payment_admin_audit_log, payment_platform_key, signed_request_record, merchant_callback_endpoint
  TO payment_simulator_runtime;
GRANT UPDATE (last_hash, updated_at) ON payment_audit_chain_head TO payment_simulator_runtime;

GRANT INSERT, UPDATE (status, fault_scenario, paid_at, closed_at, updated_at) ON channel_transaction
  TO payment_simulator_runtime;
GRANT INSERT ON channel_refund TO payment_simulator_runtime;
GRANT INSERT (attempt_id, statement_id, beneficiary_id, amount, currency, channel_payout_no, status, simulate_failure),
  UPDATE (status, resolved_at, notified_at, next_notify_at) ON channel_payouts TO payment_simulator_runtime;

GRANT USAGE ON SEQUENCE payment_admin_user_id_seq, payment_admin_role_id_seq, payment_merchant_id_seq,
  payment_admin_mfa_credential_id_seq, payment_admin_mfa_recovery_code_id_seq, payment_admin_audit_log_id_seq,
  signed_request_record_id_seq, merchant_callback_endpoint_id_seq, channel_transaction_id_seq, channel_refund_id_seq
  TO payment_simulator_runtime;
