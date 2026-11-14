package vip.fairy.commerceflow.simulator.payment.service;

import vip.fairy.commerceflow.simulator.payment.domain.ChannelTransaction;
import org.springframework.stereotype.Component;

@Component
public class AlipayChannelAdapter implements ChannelAdapter {

  @Override
  public String channel() {
    return "ALIPAY";
  }

  @Override
  public CheckoutAction checkoutAction(ChannelTransaction value, String base) {
    return new CheckoutAction("REDIRECT", base + "/cashier/" + value.getCheckoutToken() + "?channel=ALIPAY", null);
  }
}
