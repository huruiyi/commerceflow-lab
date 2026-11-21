package vip.fairy.commerceflow.payment.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.payment.application.contract.v1.PaymentResultOutcomeV1;
import vip.fairy.commerceflow.payment.application.contract.v1.PaymentResultTypeV1;
import vip.fairy.commerceflow.payment.application.contract.v1.PaymentResultV1;
import vip.fairy.commerceflow.payment.infrastructure.ChannelGatewayClient;
import vip.fairy.commerceflow.payment.infrastructure.PaymentShadowProperties;
import vip.fairy.commerceflow.payment.infrastructure.PaymentShadowProperties.Effects;
import vip.fairy.commerceflow.payment.infrastructure.PaymentShadowProperties.Shadow;
import vip.fairy.commerceflow.payment.infrastructure.RemoteInventoryEffectClient;
import vip.fairy.commerceflow.payment.infrastructure.RemoteOrderEffectClient;
import vip.fairy.commerceflow.payment.infrastructure.RemoteOrderEffectClient.OrderView;
import vip.fairy.commerceflow.payment.infrastructure.RemoteOrderEffectClient.RefundAdmission;
import vip.fairy.commerceflow.payment.infrastructure.persistence.PaymentEffectMapper;

class PaymentEffectDispatcherTest {

  private static final String TRACE = "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01";

  @Test
  void closedEffectsGateDoesNotReadOutbox() {
    PaymentEffectMapper store = mock(PaymentEffectMapper.class);
    PaymentEffectDispatcher dispatcher = dispatcher(store,
      new PaymentShadowProperties(false, new Effects(false, 20), null),
      mock(RemoteOrderEffectClient.class), mock(RemoteInventoryEffectClient.class));

    dispatcher.dispatchEffects();
    dispatcher.dispatchRefundCommands();

    verifyNoInteractions(store);
  }

  @Test
  void enabledEffectsGateDoesNotRunWhileShadowCopyIsEnabled() {
    PaymentEffectMapper store = mock(PaymentEffectMapper.class);
    PaymentEffectDispatcher dispatcher = dispatcher(store,
      new PaymentShadowProperties(true, new Effects(true, 20),
        new Shadow(true, 20, null)),
      mock(RemoteOrderEffectClient.class), mock(RemoteInventoryEffectClient.class));

    dispatcher.dispatchEffects();
    dispatcher.dispatchRefundCommands();

    verifyNoInteractions(store);
  }

  @Test
  void paymentSuccessWaitsForReservationThenUsesStableEffectsOnRetry() {
    PaymentEffectMapper store = mock(PaymentEffectMapper.class);
    RemoteOrderEffectClient orders = mock(RemoteOrderEffectClient.class);
    RemoteInventoryEffectClient inventory = mock(RemoteInventoryEffectClient.class);
    PaymentEffectDispatcher dispatcher = dispatcher(store,
      new PaymentShadowProperties(true, new Effects(true, 20), null), orders, inventory);
    OrderView pending = new OrderView(41L, "ORD-41", 7L, "PENDING_PAYMENT", "WAITING_RESERVATION");
    OrderView paid = new OrderView(41L, "ORD-41", 7L, "PAID", "RESERVED");
    OrderView waiting = new OrderView(41L, "ORD-41", 7L, "PAID", "WAITING_COMMIT");
    OrderView unreserved = new OrderView(41L, "ORD-41", 7L, "PAID", "WAITING_RESERVATION");
    when(orders.order(41L)).thenReturn(pending, unreserved, paid, paid, waiting);
    PaymentResultV1 result = new PaymentResultV1(1, "result-41",
      PaymentResultTypeV1.PAYMENT_SUCCEEDED, PaymentResultOutcomeV1.SUCCEEDED, Instant.now(),
      "payment-simulator-service", 41L, "PAY-41", null, "SUCCESS", null,
      "Signed payment", "payment-41", "callback-41", TRACE);
    String payload = new ObjectMapper().writeValueAsString(Map.of(
      "result", result, "applicationStatus", "APPLIED_PAYMENT_SUCCEEDED"));

    assertThatThrownBy(() -> dispatcher.apply("result-41", "PAYMENT_SUCCEEDED", payload))
      .isInstanceOf(IllegalStateException.class).hasMessageContaining("WAITING_RESERVATION");
    verify(orders, never()).startInventoryCommit("result-41:order:inventory-wait", 41L);
    verifyNoInteractions(inventory);

    dispatcher.apply("result-41", "PAYMENT_SUCCEEDED", payload);

    InOrder effects = inOrder(orders, inventory);
    effects.verify(orders).status("result-41:order:paid", 41L, "PENDING_PAYMENT", "PAID");
    effects.verify(orders).startInventoryCommit("result-41:order:inventory-wait", 41L);
    effects.verify(inventory).commit("result-41:inventory:commit", 41L, "result-41", TRACE);
    effects.verify(orders).inventory("result-41:order:inventory-committed", 41L,
      "WAITING_COMMIT", "COMMITTED");
    effects.verify(orders).event("result-41:order:event", 41L, 7L, "PAYMENT_SUCCEEDED",
      "PAID", "PAID", "Payment result applied by payment-orchestration");
  }

  @Test
  void rejectedOrderAdmissionDoesNotEnqueueChannelRefund() {
    PaymentEffectMapper store = mock(PaymentEffectMapper.class);
    RemoteOrderEffectClient orders = mock(RemoteOrderEffectClient.class);
    PaymentEffectDispatcher dispatcher = dispatcher(store,
      new PaymentShadowProperties(true, new Effects(true, 20), null), orders,
      mock(RemoteInventoryEffectClient.class));
    when(orders.startRefund("event-41:order:refund-admission", 41L, "REF-41"))
      .thenReturn(new RefundAdmission(false, null));

    dispatcher.apply("event-41", "REFUND_REQUESTED",
      new ObjectMapper().writeValueAsString(Map.of("orderId", 41L, "refundNumber", "REF-41", "traceparent", TRACE)));

    verify(store).rejectRefundAdmission("REF-41");
    verifyNoMoreInteractions(store);
  }

  private PaymentEffectDispatcher dispatcher(PaymentEffectMapper store,
                                             PaymentShadowProperties properties,
                                             RemoteOrderEffectClient orders,
                                             RemoteInventoryEffectClient inventory) {
    return new PaymentEffectDispatcher(store, new ObjectMapper(), properties, orders, inventory,
      mock(RefundApplicationService.class), mock(ChannelGatewayClient.class),
      mock(vip.fairy.commerceflow.payment.infrastructure.OffsiteNotificationClient.class), "",
      new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
  }
}
