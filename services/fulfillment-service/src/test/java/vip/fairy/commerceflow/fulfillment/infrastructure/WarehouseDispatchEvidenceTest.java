package vip.fairy.commerceflow.fulfillment.infrastructure;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import vip.fairy.commerceflow.fulfillment.domain.ExecutionTask;
import vip.fairy.commerceflow.fulfillment.domain.FulfillmentCommand.Operation;

import static org.assertj.core.api.Assertions.assertThat;

class WarehouseDispatchEvidenceTest {

  private static final String SECRET = "test-warehouse-receipt-key";
  private final String commandId = UUID.randomUUID().toString();
  private final ExecutionTask task = new ExecutionTask(commandId, 42, 7, Operation.SHIP, "ORD-42", "Carrier", "TRACK-42",
    null, null, "WAREHOUSE", "REVIEW_REQUIRED", null, """
      [{"orderLineId":18,"quantity":2}]
      """, 3L);
  private final JsonMapper mapper = JsonMapper.builder().build();
  private HttpServer warehouse;
  private RemoteFulfillmentDownstream downstream;
  private volatile int status = 200;
  private volatile String receipt;
  private volatile String signingKey = SECRET;

  @BeforeEach
  void startWarehouse() throws IOException {
    receipt = """
      {"outboundId":"%s","orderId":42,"merchantId":7,"warehouseId":3,"status":"DISPATCHED",
      "planned":[{"orderLineId":18,"productId":11,"quantity":2}],
      "actual":[{"orderLineId":18,"quantity":1}],"decidedAt":"2026-11-28T00:00:00Z"}
      """.formatted(commandId);
    warehouse = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    warehouse.createContext("/internal/v1/outbounds/query", this::respond);
    warehouse.start();
    downstream = new RemoteFulfillmentDownstream(mapper, "", "", "", "", "", "",
      "http://127.0.0.1:" + warehouse.getAddress().getPort(), SECRET);
  }

  @AfterEach
  void stopWarehouse() {
    if (warehouse != null) warehouse.stop(0);
  }

  @Test
  void acceptsSignedOriginalReceiptWithAValidPartialDispatch() {
    assertThat(downstream.hasMatchingWarehouseDispatch(task)).isTrue();
  }

  @Test
  void refusesMissingPendingAndUnsignedReceipts() {
    String original = receipt;
    status = 404;
    assertThat(downstream.hasMatchingWarehouseDispatch(task)).isFalse();
    status = 200;
    receipt = original.replace("DISPATCHED", "PENDING");
    assertThat(downstream.hasMatchingWarehouseDispatch(task)).isFalse();
    receipt = original;
    signingKey = "wrong-key";
    assertThat(downstream.hasMatchingWarehouseDispatch(task)).isFalse();
  }

  @Test
  void refusesSignedReceiptsForOtherOrdersOrWithDifferentLinesOrCounts() {
    String original = receipt;
    receipt = original.replace("\"orderId\":42", "\"orderId\":43");
    assertThat(downstream.hasMatchingWarehouseDispatch(task)).isFalse();
    receipt = original.replace("\"warehouseId\":3", "\"warehouseId\":4");
    assertThat(downstream.hasMatchingWarehouseDispatch(task)).isFalse();
    receipt = original.replace("\"orderLineId\":18", "\"orderLineId\":19");
    assertThat(downstream.hasMatchingWarehouseDispatch(task)).isFalse();
    receipt = original.replace("\"quantity\":1", "\"quantity\":3");
    assertThat(downstream.hasMatchingWarehouseDispatch(task)).isFalse();
  }

  private void respond(HttpExchange request) throws IOException {
    try (request) {
      var body = mapper.readTree(request.getRequestBody().readAllBytes());
      if (!commandId.equals(body.path("outboundId").asText())) {
        request.sendResponseHeaders(400, -1);
        return;
      }
      String payload = receipt;
      byte[] data = payload.getBytes(StandardCharsets.UTF_8);
      String nonce = request.getRequestHeaders().getFirst("X-CommerceFlow-Request-Id");
      request.getResponseHeaders().set("Content-Type", "application/json");
      request.getResponseHeaders().set("X-CommerceFlow-Receipt-Signature", sign(nonce + "\n" + payload));
      request.sendResponseHeaders(status, data.length);
      request.getResponseBody().write(data);
    }
  }

  private String sign(String value) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(signingKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException failure) {
      throw new IllegalStateException(failure);
    }
  }
}
