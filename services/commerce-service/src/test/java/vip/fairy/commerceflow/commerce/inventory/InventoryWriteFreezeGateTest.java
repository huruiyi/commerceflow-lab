package vip.fairy.commerceflow.commerce.inventory;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import vip.fairy.commerceflow.commerce.inventory.infrastructure.transport.InventoryWriteFreezeGate;

class InventoryWriteFreezeGateTest {

  @Test
  void allowsWritesWhenTheCutoverWindowIsOpen() {
    assertThatCode(() -> new InventoryWriteFreezeGate(false).requireWritable())
      .doesNotThrowAnyException();
  }

  @Test
  void rejectsWritesDuringFinalCatchUp() {
    assertThatThrownBy(() -> new InventoryWriteFreezeGate(true).requireWritable())
      .isInstanceOf(ResponseStatusException.class)
      .hasMessageContaining("frozen");
  }
}
