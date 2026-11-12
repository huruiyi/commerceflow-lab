package vip.fairy.commerceflow.simulator.payment.service;

import vip.fairy.commerceflow.simulator.payment.domain.ChannelTransaction;
import org.springframework.stereotype.Component;

@Component
public class WechatPayChannelAdapter implements ChannelAdapter {
  @Override
  public String channel() {
    return "WECHAT_PAY";
  }

  @Override
  public CheckoutAction checkoutAction(ChannelTransaction value, String base) {
    String url = base + "/cashier/" + value.getCheckoutToken() + "?channel=WECHAT_PAY";
    return new CheckoutAction("QR_CODE", url, "weixin://wxpay/bizpayurl?pr=" + value.getCheckoutToken());
  }
}
