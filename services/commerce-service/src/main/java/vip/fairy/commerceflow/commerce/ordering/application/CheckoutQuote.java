package vip.fairy.commerceflow.commerce.ordering.application;

import java.math.BigDecimal;

public record CheckoutQuote(String quoteId, BigDecimal productSubtotal, BigDecimal discountAmount,
                            BigDecimal shippingFee, BigDecimal totalAmount, String couponId) {
}
