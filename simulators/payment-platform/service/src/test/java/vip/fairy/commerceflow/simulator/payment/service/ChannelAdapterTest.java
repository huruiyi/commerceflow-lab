package vip.fairy.commerceflow.simulator.payment.service;

import static org.assertj.core.api.Assertions.assertThat;

import vip.fairy.commerceflow.simulator.payment.domain.ChannelTransaction;
import org.junit.jupiter.api.Test;

class ChannelAdapterTest {
  private final ChannelTransaction transaction = transaction();

  @Test
  void alipayUsesRedirectAction() {
    ChannelAdapter.CheckoutAction action = new AlipayChannelAdapter()
      .checkoutAction(transaction, "http://localhost:14201");
    assertThat(action.type()).isEqualTo("REDIRECT");
    assertThat(action.checkoutUrl()).endsWith("/cashier/checkout-token?channel=ALIPAY");
  }

  @Test
  void wechatUsesQrCodeAction() {
    ChannelAdapter.CheckoutAction action = new WechatPayChannelAdapter()
      .checkoutAction(transaction, "http://localhost:14201");
    assertThat(action.type()).isEqualTo("QR_CODE");
    assertThat(action.qrPayload()).isEqualTo("weixin://wxpay/bizpayurl?pr=checkout-token");
  }

  @Test
  void unionPayUsesGatewayAction() {
    ChannelAdapter.CheckoutAction action = new UnionPayChannelAdapter()
      .checkoutAction(transaction, "http://localhost:14201");
    assertThat(action.type()).isEqualTo("GATEWAY");
    assertThat(action.checkoutUrl()).endsWith("/cashier/checkout-token?channel=UNION_PAY");
  }

  private static ChannelTransaction transaction() {
    ChannelTransaction value = new ChannelTransaction();
    value.setCheckoutToken("checkout-token");
    return value;
  }
}
