package vip.fairy.commerceflow.commerce.ordering.infrastructure.remote;

import vip.fairy.commerceflow.commerce.shared.application.ExternalServiceUnavailableException;

public class OrderRemoteUnavailableException extends ExternalServiceUnavailableException {

  public OrderRemoteUnavailableException(Throwable cause) {
    super("ORDER_SERVICE_UNAVAILABLE", "Order service is unavailable; retry the request", cause);
  }
}
