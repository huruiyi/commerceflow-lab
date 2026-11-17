package vip.fairy.commerceflow.payment.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.payment.application.RefundApplicationService;
import vip.fairy.commerceflow.payment.application.RefundApplicationService.RefundReceipt;
import vip.fairy.commerceflow.payment.application.contract.v1.PaymentContractSemantics;
import vip.fairy.commerceflow.payment.application.contract.v1.RefundCreateCommandV1;
import vip.fairy.commerceflow.payment.infrastructure.PaymentShadowProperties;
import vip.fairy.commerceflow.payment.infrastructure.RemoteOrderEffectClient;

@RestController
@RequestMapping("/internal/v1/payments/refunds")
public class RefundCommandController {

  private final PaymentShadowProperties properties;
  private final RefundApplicationService refunds;
  private final RemoteOrderEffectClient orders;
  private final ObjectMapper mapper;

  public RefundCommandController(PaymentShadowProperties properties,
                                 RefundApplicationService refunds,
                                 RemoteOrderEffectClient orders,
                                 ObjectMapper mapper) {
    this.properties = properties;
    this.refunds = refunds;
    this.orders = orders;
    this.mapper = mapper;
  }

  @PostMapping
  RefundReceipt create(@RequestBody String payload, HttpServletRequest request) {
    if (!properties.acceptingTraffic() || properties.effects() == null
      || !properties.effects().enabled() || properties.shadow() == null
      || properties.shadow().enabled()) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
        "Payment effects gate is closed");
    }
    if (!PaymentContractSemantics.sha256(payload)
      .equals(request.getHeader("X-CommerceFlow-Content-SHA256"))) {
      throw new IllegalArgumentException("Refund payload hash is invalid");
    }
    RefundCreateCommandV1 command = mapper.readValue(payload, RefundCreateCommandV1.class);
    return refunds.request(command, orders.order(command.orderId()));
  }
}
