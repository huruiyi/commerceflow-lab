package vip.fairy.commerceflow.commerce.ordering;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.commerce.inventory.application.contract.v1.InventoryCommandV1;
import vip.fairy.commerceflow.commerce.inventory.application.port.InventoryCommandPort;
import vip.fairy.commerceflow.commerce.ordering.application.OrderCancellationProjection;
import vip.fairy.commerceflow.commerce.ordering.application.port.OrderEffectInboxPort;
import vip.fairy.commerceflow.commerce.ordering.application.port.OrderStorePort;
import vip.fairy.commerceflow.commerce.ordering.domain.ShopOrderView;

class OrderCancellationProjectionTest {

  @Test
  void lateReserveResultCannotDowngradeCommittedInventory() {
    OrderStorePort orders = mock(OrderStorePort.class);
    ShopOrderView committed = mock(ShopOrderView.class);
    when(committed.getInventoryStatus()).thenReturn("COMMITTED");
    when(orders.lockById(501L)).thenReturn(Optional.of(committed));
    OrderCancellationProjection projection = new OrderCancellationProjection(mock(ObjectMapper.class),
      mock(OrderEffectInboxPort.class), mock(InventoryCommandPort.class), orders);

    projection.succeeded(InventoryCommandV1.reserve("reserve-501", 501L, List.of(
      new InventoryCommandV1.Item(1L, "Learning Laptop", 1))));

    verify(orders, never()).updateInventoryStatus(501L, "COMMITTED", "RESERVED");
  }

  @Test
  void commitResultAdvancesWaitingCommitInventory() {
    OrderStorePort orders = mock(OrderStorePort.class);
    ShopOrderView waiting = mock(ShopOrderView.class);
    when(waiting.getInventoryStatus()).thenReturn("WAITING_COMMIT");
    when(orders.lockById(502L)).thenReturn(Optional.of(waiting));
    when(orders.updateInventoryStatus(502L, "WAITING_COMMIT", "COMMITTED")).thenReturn(1);
    OrderCancellationProjection projection = new OrderCancellationProjection(mock(ObjectMapper.class),
      mock(OrderEffectInboxPort.class), mock(InventoryCommandPort.class), orders);

    projection.succeeded(InventoryCommandV1.commit("commit-502", 502L, "payment-502"));

    verify(orders).updateInventoryStatus(502L, "WAITING_COMMIT", "COMMITTED");
  }
}
