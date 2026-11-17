package vip.fairy.commerceflow.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ShadowTableTest {

  @Test
  void freezesFourteenOwnedTablesAndStableKeys() {
    assertThat(ShadowTable.ALL).hasSize(14);
    assertThat(ShadowTable.named("payment_records").keyColumn()).isEqualTo("id");
    assertThat(ShadowTable.named("payment_idempotency_locks").keyColumn())
      .isEqualTo("idempotency_key");
    assertThat(ShadowTable.named("payment_process_managers").keyColumn())
      .isEqualTo("process_id");
    assertThat(ShadowTable.named("payment_command_outbox").keyColumn())
      .isEqualTo("command_id");
    assertThat(ShadowTable.named("payment_result_inbox").keyColumn()).isEqualTo("result_id");
  }
}
