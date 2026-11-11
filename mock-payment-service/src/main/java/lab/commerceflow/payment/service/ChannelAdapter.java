package lab.commerceflow.payment.service;

import lab.commerceflow.payment.domain.ChannelTransaction;

public interface ChannelAdapter {
  String channel();
  CheckoutAction checkoutAction(ChannelTransaction transaction, String checkoutBaseUrl);

  record CheckoutAction(String type, String checkoutUrl, String qrPayload) {
  }
}
