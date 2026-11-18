package vip.fairy.commerceflow.fulfillment.application;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import vip.fairy.commerceflow.fulfillment.domain.OrderMerchant;
import vip.fairy.commerceflow.fulfillment.domain.ShadowRow;

import static org.assertj.core.api.Assertions.assertThat;

class ShadowDatasetTest {

  @Test
  void dependencyIsolationClearsWhenOrderOwnershipReturns() {
    List<ShadowRow> rows = List.of(
      new ShadowRow(ShadowDataset.COMMANDS, "command", """
        {"command_id":"command","order_id":1}
        """),
      new ShadowRow(ShadowDataset.MERCHANT, "command", """
        {"command_id":"command","order_id":1,"merchant_id":7}
        """),
      new ShadowRow(ShadowDataset.MERCHANT_RECOVERY, "1", """
        {"id":1,"command_id":"command"}
        """),
      new ShadowRow(ShadowDataset.LOCKS, "1", """
        {"order_id":1}
        """));
    var mapper = JsonMapper.builder().build();
    ShadowDataset orphaned = ShadowDataset.classify(mapper, rows, Set.of(1L), Set.of());
    assertThat(orphaned.isolated()).hasSize(3);
    assertThat(orphaned.valid()).containsOnlyKeys(rows.getLast().key());
    ShadowDataset restored = ShadowDataset.classify(mapper, rows, Set.of(1L), Set.of(new OrderMerchant(1, 7)));
    assertThat(restored.isolated()).isEmpty();
    assertThat(restored.valid()).hasSize(4);
  }
}
