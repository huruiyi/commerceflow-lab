package vip.fairy.commerceflow.payment.application;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;
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

class PaymentEffectDispatcherTest {

  private static final String TRACE = "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01";

  @Test
  void closedEffectsGateDoesNotReadOutbox() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    PaymentEffectDispatcher dispatcher = dispatcher(jdbc,
      new PaymentShadowProperties(false, new Effects(false, 20), null),
      mock(RemoteOrderEffectClient.class), mock(RemoteInventoryEffectClient.class));

    dispatcher.dispatchEffects();
    dispatcher.dispatchRefundCommands();

    verifyNoInteractions(jdbc);
  }

  @Test
  void enabledEffectsGateDoesNotRunWhileShadowCopyIsEnabled() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    PaymentEffectDispatcher dispatcher = dispatcher(jdbc,
      new PaymentShadowProperties(true, new Effects(true, 20),
        new Shadow(true, 20, null)),
      mock(RemoteOrderEffectClient.class), mock(RemoteInventoryEffectClient.class));

    dispatcher.dispatchEffects();
    dispatcher.dispatchRefundCommands();

    verifyNoInteractions(jdbc);
  }

  @Test
  void paymentSuccessUsesStableOrderAndInventoryEffects() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    RemoteOrderEffectClient orders = mock(RemoteOrderEffectClient.class);
    RemoteInventoryEffectClient inventory = mock(RemoteInventoryEffectClient.class);
    PaymentEffectDispatcher dispatcher = dispatcher(jdbc,
      new PaymentShadowProperties(true, new Effects(true, 20), null), orders, inventory);
    OrderView pending = new OrderView(41L, "ORD-41", 7L, "PENDING_PAYMENT", "RESERVED");
    OrderView paid = new OrderView(41L, "ORD-41", 7L, "PAID", "RESERVED");
    OrderView waiting = new OrderView(41L, "ORD-41", 7L, "PAID", "WAITING_COMMIT");
    when(orders.order(41L)).thenReturn(pending, paid, waiting);
    PaymentResultV1 result = new PaymentResultV1(1, "result-41",
      PaymentResultTypeV1.PAYMENT_SUCCEEDED, PaymentResultOutcomeV1.SUCCEEDED, Instant.now(),
      "payment-simulator-service", 41L, "PAY-41", null, "SUCCESS", null,
      "Signed payment", "payment-41", "callback-41", TRACE);
    String payload = new ObjectMapper().writeValueAsString(Map.of(
      "result", result, "applicationStatus", "APPLIED_PAYMENT_SUCCEEDED"));

    dispatcher.apply("result-41", "PAYMENT_SUCCEEDED", payload);

    InOrder effects = inOrder(orders, inventory);
    effects.verify(orders).status("result-41:order:paid", 41L, "PENDING_PAYMENT", "PAID");
    effects.verify(orders).inventory("result-41:order:inventory-wait", 41L, "RESERVED",
      "WAITING_COMMIT");
    effects.verify(inventory).commit("result-41:inventory:commit", 41L, "result-41", TRACE);
    effects.verify(orders).inventory("result-41:order:inventory-committed", 41L,
      "WAITING_COMMIT", "COMMITTED");
    effects.verify(orders).event("result-41:order:event", 41L, 7L, "PAYMENT_SUCCEEDED",
      "PENDING_PAYMENT", "PAID", "Payment result applied by payment-orchestration");
  }

  private PaymentEffectDispatcher dispatcher(JdbcTemplate jdbc,
                                             PaymentShadowProperties properties,
                                             RemoteOrderEffectClient orders,
                                             RemoteInventoryEffectClient inventory) {
    return new PaymentEffectDispatcher(jdbc, new ObjectMapper(), properties, orders, inventory,
      mock(RefundApplicationService.class), mock(ChannelGatewayClient.class));
  }
}
