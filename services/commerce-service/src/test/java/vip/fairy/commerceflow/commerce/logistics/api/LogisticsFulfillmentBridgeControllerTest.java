package vip.fairy.commerceflow.commerce.logistics.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
import vip.fairy.commerceflow.commerce.logistics.application.contract.LogisticsFulfillmentV1;
import vip.fairy.commerceflow.commerce.logistics.application.contract.LogisticsFulfillmentV1.Command;
import vip.fairy.commerceflow.commerce.logistics.application.contract.LogisticsFulfillmentV1.Operation;
import vip.fairy.commerceflow.commerce.logistics.application.contract.LogisticsFulfillmentV1.Receipt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LogisticsFulfillmentBridgeControllerTest {

  @Test
  void signedCommandReachesExistingPortAndTamperedBodyDoesNot() throws Exception {
    String secret = "isolated-test-key";
    String path = "/internal/v1/logistics/fulfillment/commands";
    var mapper = JsonMapper.builder().build();
    var logistics = mock(LogisticsFulfillmentV1.class);
    var controller = new LogisticsFulfillmentBridgeController(logistics, mapper, secret);
    var command = new Command(UUID.randomUUID().toString(), Operation.ENSURE_SHIPMENT,
      1L, "TEST-ONE", "Carrier", "TRACK", null);
    String payload = mapper.writeValueAsString(command);
    String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
      .digest(payload.getBytes(StandardCharsets.UTF_8)));
    String timestamp = Long.toString(Instant.now().getEpochSecond());
    String requestId = UUID.randomUUID().toString();
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    String signature = HexFormat.of().formatHex(mac.doFinal(
      String.join("\n", "POST", path, timestamp, requestId, digest).getBytes(StandardCharsets.UTF_8)));
    var request = new MockHttpServletRequest("POST", path);
    request.addHeader("X-CommerceFlow-Service", "fulfillment-service");
    request.addHeader("X-CommerceFlow-Timestamp", timestamp);
    request.addHeader("X-CommerceFlow-Request-Id", requestId);
    request.addHeader("X-CommerceFlow-Content-SHA256", digest);
    request.addHeader("X-CommerceFlow-Signature", signature);
    var bad = assertThrows(ResponseStatusException.class, () -> controller.command(payload + " ", request));
    assertEquals(HttpStatus.UNAUTHORIZED, bad.getStatusCode());
    verifyNoInteractions(logistics);

    var receipt = new Receipt(command.commandId(), "PLATFORM", 0);
    when(logistics.execute(command)).thenReturn(receipt);
    assertEquals(receipt, controller.command(payload, request));
  }
}
