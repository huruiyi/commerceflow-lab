package vip.fairy.commerceflow.commerce.shared.application;

public interface PaymentCancellationCrashProbe {

  enum Phase {
    AFTER_CANCELLATION_BEGIN,
    AFTER_CHANNEL_CLOSE,
    AFTER_PAYMENT_RECORD
  }

  void pause(Long orderId, Phase phase);
}
