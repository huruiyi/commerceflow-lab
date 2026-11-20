package vip.fairy.commerceflow.commerce.ordering;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.fairy.commerceflow.commerce.identity.domain.AppUser;
import vip.fairy.commerceflow.commerce.inventory.application.port.InventoryReservationQuery;
import vip.fairy.commerceflow.commerce.inventory.application.port.InventoryReservationQuery.Allocation;
import vip.fairy.commerceflow.commerce.ordering.api.CreatePaymentRequest;
import vip.fairy.commerceflow.commerce.ordering.application.PurchaseOrderService;
import vip.fairy.commerceflow.commerce.ordering.application.port.OrderStorePort;
import vip.fairy.commerceflow.commerce.ordering.domain.OrderItemView;
import vip.fairy.commerceflow.commerce.ordering.domain.ShopOrderView;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.PaymentChannelV1;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.PaymentOrchestrationV1;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.PaymentWriteAdmissionV1;
import vip.fairy.commerceflow.commerce.shared.application.BusinessRuleException;

@ExtendWith(MockitoExtension.class)
class PurchaseOrderPaymentAdmissionTest {

  @Mock
  private OrderStorePort orders;

  @Mock
  private InventoryReservationQuery reservations;

  @Mock
  private PaymentOrchestrationV1 payments;

  @Mock
  private PaymentWriteAdmissionV1 paymentWrites;

  @InjectMocks
  private PurchaseOrderService purchaseOrders;

  @Test
  void staleReservedProjectionCannotOpenPaymentForMissingOrMismatchedStock() {
    AppUser buyer = mock(AppUser.class);
    ShopOrderView order = mock(ShopOrderView.class);
    OrderItemView item = mock(OrderItemView.class);
    when(buyer.getId()).thenReturn(10L);
    when(orders.find(10L, 20L)).thenReturn(Optional.of(order));
    when(order.getStatus()).thenReturn("PENDING_PAYMENT");
    when(order.getPaymentDueAt()).thenReturn(Instant.now().plusSeconds(60));
    when(order.getInventoryStatus()).thenReturn("RESERVED");
    when(orders.items(20L)).thenReturn(List.of(item));
    when(item.getProductId()).thenReturn(30L);
    when(item.getQuantity()).thenReturn(2);

    CreatePaymentRequest request = new CreatePaymentRequest("payment-test", false, PaymentChannelV1.ALIPAY);
    for (List<Allocation> actual : List.of(List.<Allocation>of(),
      List.of(new Allocation(1, 30, 1, "RESERVED", 1)),
      List.of(new Allocation(2, 40, 2, "RESERVED", 1)))) {
      when(reservations.activeAllocations(20L)).thenReturn(actual);
      assertThatThrownBy(() -> purchaseOrders.pay(buyer, 20L, request))
        .isInstanceOf(BusinessRuleException.class).hasMessageContaining("reservations");
    }
    verifyNoInteractions(payments);
  }

  @Test
  void paymentCommandTakesMerchantFromOrderItems() {
    AppUser buyer = mock(AppUser.class);
    ShopOrderView order = mock(ShopOrderView.class);
    OrderItemView item = mock(OrderItemView.class);
    when(buyer.getId()).thenReturn(10L);
    when(orders.find(10L, 20L)).thenReturn(Optional.of(order));
    when(order.getId()).thenReturn(20L);
    when(order.getStatus()).thenReturn("PENDING_PAYMENT");
    when(order.getPaymentDueAt()).thenReturn(Instant.now().plusSeconds(60));
    when(order.getInventoryStatus()).thenReturn("RESERVED");
    when(orders.items(20L)).thenReturn(List.of(item));
    when(item.getProductId()).thenReturn(30L);
    when(item.getMerchantId()).thenReturn(40L);
    when(item.getQuantity()).thenReturn(2);
    when(reservations.activeAllocations(20L)).thenReturn(List.of(new Allocation(1, 30, 2, "RESERVED", 1)));
    when(orders.freezeWarehouseAllocation(any(), any())).thenReturn(true);
    var gatewayStop = new IllegalStateException("Stop after capturing the outgoing payment command");
    when(payments.create(any())).thenThrow(gatewayStop);

    var request = new CreatePaymentRequest("payment-test", false, PaymentChannelV1.ALIPAY);
    assertThatThrownBy(() -> purchaseOrders.pay(buyer, 20L, request)).isSameAs(gatewayStop);
    verify(payments).create(argThat(command -> command.orderId() == 20L && command.orderMerchantId() == 40L));
  }
}
