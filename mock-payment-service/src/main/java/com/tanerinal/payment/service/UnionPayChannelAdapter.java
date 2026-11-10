package com.tanerinal.payment.service;

import com.tanerinal.payment.domain.ChannelTransaction;
import org.springframework.stereotype.Component;

@Component
public class UnionPayChannelAdapter implements ChannelAdapter {
  @Override public String channel() { return "UNION_PAY"; }
  @Override public CheckoutAction checkoutAction(ChannelTransaction value, String base) {
    return new CheckoutAction("GATEWAY", base + "/cashier/" + value.getCheckoutToken() + "?channel=UNION_PAY", null);
  }
}
