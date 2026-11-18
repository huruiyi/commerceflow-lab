package vip.fairy.commerceflow.payment.infrastructure.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import vip.fairy.commerceflow.payment.infrastructure.PaymentShadowProperties;

class PaymentRuntimeMetricsTest {

  @Test
  void exposesBacklogsAndCutoverMode() {
    PaymentRuntimeMetricsStore store = mock(PaymentRuntimeMetricsStore.class);
    when(store.snapshot()).thenReturn(new PaymentRuntimeMetricsStore.Snapshot(2, 3, 4, 1, 7, 5, 6));
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    PaymentShadowProperties properties = new PaymentShadowProperties(true,
      new PaymentShadowProperties.Effects(true, 20),
      new PaymentShadowProperties.Shadow(false, 500, null));
    PaymentRuntimeMetrics metrics = new PaymentRuntimeMetrics(store, properties, registry);

    metrics.refresh();

    assertGauge(registry, "commerceflow.payment.outbox.pending", 2);
    assertGauge(registry, "commerceflow.payment.command.outbox.pending", 3);
    assertGauge(registry, "commerceflow.payment.process.pending", 4);
    assertGauge(registry, "commerceflow.payment.process.recovery.required", 1);
    assertGauge(registry, "commerceflow.payment.reconciliation.open", 7);
    assertGauge(registry, "commerceflow.payment.orphan.open", 5);
    assertGauge(registry, "commerceflow.payment.refund.processing", 6);
    assertGauge(registry, "commerceflow.payment.metrics.refresh.success", 1);
    assertGauge(registry, "commerceflow.payment.accepting.traffic", 1);
    assertGauge(registry, "commerceflow.payment.effects.enabled", 1);
    assertGauge(registry, "commerceflow.payment.shadow.enabled", 0);
  }

  @Test
  void reportsMetricRefreshFailure() {
    PaymentRuntimeMetricsStore store = mock(PaymentRuntimeMetricsStore.class);
    when(store.snapshot()).thenReturn(new PaymentRuntimeMetricsStore.Snapshot(0, 0, 1, 1, 4, 0, 0))
      .thenThrow(new IllegalStateException("database unavailable"));
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    PaymentShadowProperties properties = new PaymentShadowProperties(true,
      new PaymentShadowProperties.Effects(true, 20),
      new PaymentShadowProperties.Shadow(false, 500, null));
    PaymentRuntimeMetrics metrics = new PaymentRuntimeMetrics(store, properties, registry);

    metrics.refresh();
    metrics.refresh();

    assertGauge(registry, "commerceflow.payment.metrics.refresh.success", 0);
    assertGauge(registry, "commerceflow.payment.reconciliation.open", 4);
    assertGauge(registry, "commerceflow.payment.process.recovery.required", 1);
  }

  private void assertGauge(SimpleMeterRegistry registry, String name, double expected) {
    assertThat(registry.get(name).gauge().value()).isEqualTo(expected);
  }
}
