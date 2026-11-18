package vip.fairy.commerceflow.commerce.ordering.application.port;

import vip.fairy.commerceflow.commerce.ordering.domain.OrderAddressView;
import vip.fairy.commerceflow.commerce.ordering.domain.OrderEventView;
import vip.fairy.commerceflow.commerce.ordering.domain.OrderItemView;

import java.util.List;

public interface OrderFulfillmentPort {

  List<OrderItemView> items(Long orderId);

  OrderAddressView address(Long orderId);

  List<OrderEventView> events(Long orderId);
}
