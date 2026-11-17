package vip.fairy.commerceflow.order.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import vip.fairy.commerceflow.order.application.contract.v1.OrderCommandV1;
import vip.fairy.commerceflow.order.application.contract.v1.OrderContractSemantics;
import vip.fairy.commerceflow.order.application.contract.v1.OrderResultV1;
import vip.fairy.commerceflow.order.infrastructure.OrderShadowProperties;

@RestController
@RequestMapping("/internal/v1/orders")
public class RemoteOrderV1Controller {

  private final OrderShadowProperties properties;

  public RemoteOrderV1Controller(OrderShadowProperties properties) {
    this.properties = properties;
  }

  @PostMapping("/commands")
  ContractReceipt command(@RequestBody OrderCommandV1 command) {
    requireTraffic();
    return new ContractReceipt(command.commandId(), OrderContractSemantics.fingerprint(command), "VALIDATED");
  }

  @PostMapping("/results")
  ContractReceipt result(@RequestBody OrderResultV1 result) {
    requireTraffic();
    return new ContractReceipt(result.resultId(), OrderContractSemantics.fingerprint(result), "VALIDATED");
  }

  private void requireTraffic() {
    if (!properties.acceptingTraffic()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
      "Order cutover gate is closed; shadow replication remains available");
  }

  record ContractReceipt(String messageId, String payloadFingerprint, String status) {
  }
}
