package vip.fairy.commerceflow.commerce.payment.application;

import java.util.concurrent.atomic.AtomicBoolean;

import vip.fairy.commerceflow.commerce.payment.infrastructure.PaymentCancellationCrashLabProperties;
import vip.fairy.commerceflow.commerce.payment.infrastructure.PaymentCancellationCrashLabProperties.Phase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class PaymentCancellationCrashLab {
  private static final Logger LOGGER = LoggerFactory.getLogger(PaymentCancellationCrashLab.class);

  private final PaymentCancellationCrashLabProperties properties;
  private final AtomicBoolean paused = new AtomicBoolean();

  public PaymentCancellationCrashLab(PaymentCancellationCrashLabProperties properties) {
    this.properties = properties;
  }

  public void pause(Long orderId, Phase phase) {
    if (!properties.isEnabled() || properties.getOrderId() != orderId
      || properties.getPhase() != phase || !paused.compareAndSet(false, true)) return;
    LOGGER.warn("PAYMENT_CANCELLATION_CRASH_LAB_PAUSED orderId={} phase={}", orderId, phase);
    try {
      Thread.sleep(properties.getPause().toMillis());
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Payment/cancellation crash lab pause was interrupted", exception);
    }
  }
}
