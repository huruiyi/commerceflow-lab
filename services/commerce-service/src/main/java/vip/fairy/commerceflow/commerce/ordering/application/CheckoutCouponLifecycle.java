package vip.fairy.commerceflow.commerce.ordering.application;

import org.springframework.stereotype.Service;
import vip.fairy.commerceflow.commerce.ordering.infrastructure.CheckoutIntentMapper;

@Service
public class CheckoutCouponLifecycle {

  private final CheckoutIntentMapper intents;
  private final CouponService coupons;

  public CheckoutCouponLifecycle(CheckoutIntentMapper intents, CouponService coupons) {
    this.intents = intents;
    this.coupons = coupons;
  }

  void cancelled(Long orderId) {
    resolve(intents.lockOrder(orderId), false);
  }

  void resolve(CheckoutIntentMapper.Intent intent, boolean consumed) {
    if (intent == null || !"BOUND".equals(intent.status())) return;
    coupons.reconcile(intent.couponId(), intent.quoteId(), consumed);
    intents.resolve(intent.quoteId(), consumed ? "USED" : "RETURNED");
  }
}
