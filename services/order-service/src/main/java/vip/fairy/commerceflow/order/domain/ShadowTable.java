package vip.fairy.commerceflow.order.domain;

import java.util.List;

public record ShadowTable(String name, String keyColumn, List<String> columns) {

  public static final List<ShadowTable> ALL = List.of(
    table("shop_orders", "id", "id", "order_number", "customer_id", "status",
      "inventory_status", "total_amount", "currency", "created_at", "updated_at",
      "confirmed_at", "shipped_at", "carrier", "tracking_number", "payment_due_at",
      "cancelled_at", "cancel_reason", "received_at", "completed_at", "logistics_delivered_at"),
    table("order_items", "id", "id", "order_id", "product_id", "merchant_id", "product_name",
      "thumbnail_url", "unit_price", "quantity", "subtotal"),
    table("order_shipping_addresses", "order_id", "order_id", "recipient_name", "phone",
      "country_code", "state_province", "city", "district", "address_line1", "address_line2",
      "postal_code", "label"),
    table("order_events", "id", "id", "order_id", "actor_id", "event_type", "from_status",
      "to_status", "detail", "created_at", "trigger_source", "scheduled_task_id"),
    table("order_process_managers", "process_id", "process_id", "order_id", "process_type",
      "status", "current_step", "waiting_for", "command_id", "command_fingerprint",
      "correlation_id", "causation_id", "traceparent", "attempts", "result_deadline_at",
      "next_check_at", "last_error", "recovered_by", "recovery_reason", "recovered_at",
      "version", "created_at", "updated_at", "completed_at"),
    table("order_command_outbox", "command_id", "command_id", "process_id", "order_id",
      "target_service", "command_type", "payload_fingerprint", "payload", "status", "attempts",
      "next_attempt_at", "result_deadline_at", "lease_until", "last_error", "correlation_id",
      "causation_id", "traceparent", "created_at", "acknowledged_at"),
    table("order_result_inbox", "result_id", "result_id", "process_id", "order_id", "result_type",
      "outcome", "payload_fingerprint", "payload", "processing_status", "error_detail",
      "correlation_id", "causation_id", "traceparent", "received_at", "processed_at")
  );

  public static ShadowTable named(String name) {
    return ALL.stream().filter(table -> table.name.equals(name)).findFirst()
      .orElseThrow(() -> new IllegalArgumentException("Unknown order shadow table " + name));
  }

  private static ShadowTable table(String name, String keyColumn, String... columns) {
    return new ShadowTable(name, keyColumn, List.of(columns));
  }
}
