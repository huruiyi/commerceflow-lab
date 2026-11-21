package vip.fairy.commerceflow.simulator.logistics.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.simulator.logistics.application.TestLogisticsCallbacks;
import vip.fairy.commerceflow.simulator.logistics.application.TestLogisticsCallbacks.Result;
import vip.fairy.commerceflow.simulator.logistics.infrastructure.security.LogisticsCallbackSignature;

@RestController
public class TestLogisticsCallbackController {

  private final LogisticsCallbackSignature signatures;
  private final TestLogisticsCallbacks testCallbacks;
  private final ObjectMapper mapper;

  public TestLogisticsCallbackController(LogisticsCallbackSignature signatures, TestLogisticsCallbacks testCallbacks,
                                         ObjectMapper mapper) {
    this.signatures = signatures;
    this.testCallbacks = testCallbacks;
    this.mapper = mapper;
  }

  @PostMapping(path = {TestLogisticsCallbacks.PATH + "/success", TestLogisticsCallbacks.PATH + "/failure"},
    consumes = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<Void> receive(
      @RequestBody String body,
      @RequestHeader(value = LogisticsCallbackSignature.HEADER, required = false) String signature) {
    if (!signatures.matches(body, signature)) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "LOGISTICS_CALLBACK_SIGNATURE_INVALID");
    }
    // 已入队的回调保留原始 URL，接收端按当前设置决定结果。
    JsonNode event = mapper.readTree(body);
    Result result = testCallbacks.forDelivery(event.path("merchantShipmentNo").asText(),
      event.path("platformShipmentNo").asText());
    return result == Result.ACCEPT ? ResponseEntity.noContent().build()
      : ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
  }
}
