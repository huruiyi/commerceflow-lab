package vip.fairy.commerceflow.commerce.ordering;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.commerce.cart.application.contract.v1.RejectedCheckoutCartV1;
import vip.fairy.commerceflow.commerce.inventory.application.contract.v1.InventoryCommandResultV1;
import vip.fairy.commerceflow.commerce.inventory.application.contract.v1.InventoryCommandV1;
import vip.fairy.commerceflow.commerce.inventory.application.port.InventoryCommandPort;
import vip.fairy.commerceflow.commerce.ordering.application.OrderCancellationProjection;
import vip.fairy.commerceflow.commerce.ordering.application.port.OrderEffectInboxPort;
import vip.fairy.commerceflow.commerce.ordering.application.port.OrderStorePort;
import vip.fairy.commerceflow.commerce.ordering.domain.ShopOrderView;
import vip.fairy.commerceflow.commerce.ordering.domain.OrderItemView;

class OrderCancellationProjectionTest {

  @Test
  void lateReserveResultCannotDowngradeCommittedInventory() {
    OrderStorePort orders = mock(OrderStorePort.class);
    ShopOrderView committed = mock(ShopOrderView.class);
    when(committed.getInventoryStatus()).thenReturn("COMMITTED");
    when(orders.lockById(501L)).thenReturn(Optional.of(committed));
    OrderCancellationProjection projection = new OrderCancellationProjection(mock(ObjectMapper.class),
      mock(OrderEffectInboxPort.class), mock(InventoryCommandPort.class), orders, mock(RejectedCheckoutCartV1.class));

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
      mock(OrderEffectInboxPort.class), mock(InventoryCommandPort.class), orders, mock(RejectedCheckoutCartV1.class));

    projection.succeeded(InventoryCommandV1.commit("commit-502", 502L, "payment-502"));

    verify(orders).updateInventoryStatus(502L, "WAITING_COMMIT", "COMMITTED");
  }

  @Test
  void reviewedStockRejectionRestoresCartBeforeProjectingRejection() {
    OrderStorePort orders = mock(OrderStorePort.class);
    RejectedCheckoutCartV1 cart = mock(RejectedCheckoutCartV1.class);
    ShopOrderView waiting = mock(ShopOrderView.class);
    when(waiting.getInventoryStatus()).thenReturn("WAITING_RESERVATION");
    when(waiting.getCustomerId()).thenReturn(23L);
    when(orders.lockById(503L)).thenReturn(Optional.of(waiting));
    when(orders.items(503L)).thenReturn(List.of(mock(OrderItemView.class)));
    when(orders.updateInventoryStatus(503L, "WAITING_RESERVATION", "RESERVATION_REJECTED")).thenReturn(1);
    var projection = new OrderCancellationProjection(mock(ObjectMapper.class),
      mock(OrderEffectInboxPort.class), mock(InventoryCommandPort.class), orders, cart);
    var command = InventoryCommandV1.reserveReviewedCheckout("order:503:reserve", 503L,
      List.of(new InventoryCommandV1.Item(1L, "Learning Laptop", 1)));
    var result = mock(InventoryCommandResultV1.class);
    when(result.errorCode()).thenReturn("STOCK_UNAVAILABLE");

    projection.rejected(command, result);

    var sequence = inOrder(cart, orders);
    sequence.verify(cart).restoreMissing(org.mockito.ArgumentMatchers.eq(23L), org.mockito.ArgumentMatchers.anyList());
    sequence.verify(orders).updateInventoryStatus(503L, "WAITING_RESERVATION", "RESERVATION_REJECTED");
  }
}
