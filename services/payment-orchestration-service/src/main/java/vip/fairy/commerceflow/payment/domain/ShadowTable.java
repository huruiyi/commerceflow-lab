package vip.fairy.commerceflow.payment.domain;

import java.util.List;

public record ShadowTable(String name, String keyColumn, List<String> columns) {

  public static final List<ShadowTable> ALL = List.of(
    table("payment_merchant_accounts", "id", "id", "channel", "merchant_no", "app_id",
      "display_name", "signing_algorithm", "api_secret", "enabled", "created_at", "updated_at"),
    table("payment_records", "id", "id", "order_id", "payment_number", "idempotency_key",
      "amount", "status", "failure_reason", "created_at", "merchant_account_id", "channel",
      "currency", "request_fingerprint", "channel_transaction_no", "paid_at", "expires_at",
      "updated_at", "checkout_url", "action_type", "qr_payload", "version", "refunded_amount"),
    table("payment_notifications", "id", "id", "payment_id", "channel", "notification_id",
      "channel_transaction_no", "signature_valid", "processing_status", "payload_digest",
      "failure_reason", "received_at", "processed_at", "raw_status", "retry_count",
      "next_retry_at"),
    table("payment_events", "id", "id", "payment_id", "event_id", "event_type",
      "from_status", "to_status", "detail", "created_at"),
    table("payment_idempotency_locks", "idempotency_key", "idempotency_key", "created_at"),
    table("payment_reconciliation_issue", "id", "id", "payment_id", "issue_type", "detail",
      "status", "detected_at", "resolved_at", "resolution_code", "resolved_by",
      "resolution_detail"),
    table("payment_outbox", "id", "id", "event_id", "aggregate_type", "aggregate_id",
      "event_type", "topic", "payload", "status", "attempts", "next_attempt_at", "created_at",
      "published_at"),
    table("kafka_consumed_event", "id", "id", "consumer_group", "event_id", "topic",
      "processed_at"),
    table("refund_records", "id", "id", "refund_number", "order_id", "payment_id",
      "customer_id", "idempotency_key", "request_fingerprint", "amount", "currency",
      "reason_code", "reason_detail", "status", "inventory_status", "order_status_before_refund",
      "channel_refund_no", "failure_reason", "requested_at", "succeeded_at", "failed_at",
      "created_at", "updated_at", "version"),
    table("refund_events", "id", "id", "refund_id", "event_id", "event_type", "from_status",
      "to_status", "detail", "created_at"),
    table("refund_notifications", "id", "id", "refund_id", "channel", "notification_id",
      "channel_refund_no", "signature_valid", "processing_status", "payload_digest",
      "failure_reason", "received_at", "processed_at"),
    table("payment_process_managers", "process_id", "process_id", "order_id", "process_type",
      "status", "command_id", "command_fingerprint", "correlation_id", "causation_id",
      "traceparent", "result_deadline_at", "next_check_at", "last_error", "recovered_by",
      "recovery_reason", "recovered_at", "version", "created_at", "updated_at", "completed_at"),
    table("payment_command_outbox", "command_id", "command_id", "process_id", "order_id",
      "command_type", "payload_fingerprint", "payload", "status", "attempts", "next_attempt_at",
      "result_deadline_at", "last_error", "correlation_id", "causation_id", "traceparent",
      "created_at", "acknowledged_at"),
    table("payment_result_inbox", "result_id", "result_id", "process_id", "order_id",
      "result_type", "outcome", "payload_fingerprint", "payload", "processing_status",
      "error_detail", "correlation_id", "causation_id", "traceparent", "received_at",
      "processed_at")
  );

  public static ShadowTable named(String name) {
    return ALL.stream().filter(table -> table.name.equals(name)).findFirst()
      .orElseThrow(() -> new IllegalArgumentException("Unknown payment shadow table " + name));
  }

  private static ShadowTable table(String name, String keyColumn, String... columns) {
    return new ShadowTable(name, keyColumn, List.of(columns));
  }
}
