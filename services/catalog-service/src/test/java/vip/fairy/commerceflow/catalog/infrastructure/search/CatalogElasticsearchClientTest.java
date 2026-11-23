package vip.fairy.commerceflow.catalog.infrastructure.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import vip.fairy.commerceflow.catalog.api.CatalogProductV1;

class CatalogElasticsearchClientTest {

  @Test
  void writesLocalizedBulkDocumentsAsUtf8() throws IOException {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var request = new AtomicReference<String>();
    server.createContext("/commerceflow-test-1/_bulk", exchange -> {
      try (exchange) {
        request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        byte[] response = "{\"errors\":false}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
      }
    });
    server.start();
    try {
      var json = JsonMapper.builder().build();
      var url = "http://127.0.0.1:" + server.getAddress().getPort();
      var client = new CatalogElasticsearchClient(json, url, "commerceflow-test");
      var product = new CatalogProductV1(1, 1L, 2L, "咖啡 café", "", "", null, "", "", "ACTIVE", 1, null);
      var document = new CatalogElasticsearchClient.Document(1, 2, 0, true, "", "", "", "", "", "",
        Map.of("zh-CN", product));

      client.write(1, List.of(document), true);

      assertThat(request.get()).endsWith("\n");
      var lines = request.get().lines().toList();
      assertThat(lines).hasSize(2);
      assertThat(json.readTree(lines.get(1)).path("products").path("zh-CN").path("name").asText())
        .isEqualTo("咖啡 café");
    } finally {
      server.stop(0);
    }
  }
}
