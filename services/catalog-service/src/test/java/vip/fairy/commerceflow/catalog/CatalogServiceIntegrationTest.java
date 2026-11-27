package vip.fairy.commerceflow.catalog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import vip.fairy.commerceflow.catalog.infrastructure.security.InternalRequestSignature;
import vip.fairy.commerceflow.catalog.application.CatalogProductEventV1;
import vip.fairy.commerceflow.catalog.infrastructure.persistence.CatalogEventOutboxMapper;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
  "spring.datasource.url=jdbc:h2:mem:catalog;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
  "spring.datasource.username=sa",
  "spring.datasource.password=",
  "spring.flyway.enabled=false",
  "spring.sql.init.mode=always",
  "commerceflow.catalog-event-delivery.enabled=false"
})
@AutoConfigureMockMvc
class CatalogServiceIntegrationTest {

  @Autowired
  MockMvc mvc;
  @Autowired
  JdbcTemplate jdbc;
  @Autowired
  InternalRequestSignature signatures;
  @Autowired
  ObjectMapper json;
  @Autowired
  CatalogEventOutboxMapper outbox;

  @BeforeEach
  void product() {
    jdbc.update("DELETE FROM catalog_event_outbox");
    jdbc.update("DELETE FROM product_translations");
    jdbc.update("DELETE FROM products");
    jdbc.update("INSERT INTO products(id,name,brief,description,price,image_url,thumbnail_url,"
        + "merchant_id,lifecycle_status,business_version,updated_at) VALUES "
        + "(1,'Learning Laptop','Coding labs','Workstation',1299,'image','thumb',5,'ACTIVE',1,CURRENT_TIMESTAMP)");
    jdbc.update("INSERT INTO product_translations(product_id,locale,name,brief,description) "
      + "VALUES (1,'zh-CN','学习笔记本电脑','编程实验','工作站')");
  }

  @Test
  void signedQueryReturnsLocalizedOwnedData() throws Exception {
    String target = "/internal/v1/products/1?lang=zh-CN";
    mvc.perform(signed(get(target), "GET", target))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.name").value("学习笔记本电脑"))
      .andExpect(jsonPath("$.businessVersion").value(1));
  }

  @Test
  void merchantWriteAdvancesVersionAndAppendsOutbox() throws Exception {
    String target = "/internal/v1/merchant/products/1/price";
    mvc.perform(signed(put(target), "PUT", target)
        .header("X-Merchant-Id", "5")
        .header("X-Correlation-Id", "catalog-test")
        .header("X-Causation-Id", "price-test")
        .contentType("application/json")
        .content("{\"expectedVersion\":1,\"unitPrice\":99.99}"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.unitPrice").value(99.99))
      .andExpect(jsonPath("$.businessVersion").value(2));
    org.junit.jupiter.api.Assertions.assertEquals(1, jdbc.queryForObject(
      "SELECT COUNT(*) FROM catalog_event_outbox WHERE event_type='PRODUCT_PRICE_CHANGED' "
        + "AND correlation_id='catalog-test'", Integer.class));
  }

  @Test
  void unsignedRequestIsRejected() throws Exception {
    mvc.perform(get("/internal/v1/products/1"))
      .andExpect(status().isUnauthorized());
  }

  @Test
  void productEventV1FixtureRemainsCompatible() throws Exception {
    try (var fixture = getClass().getResourceAsStream(
      "/contracts/catalog-product-profile-updated-v1.json")) {
      org.junit.jupiter.api.Assertions.assertNotNull(fixture);
      CatalogProductEventV1 event = json.readValue(fixture, CatalogProductEventV1.class);
      event.validate();
      org.junit.jupiter.api.Assertions.assertEquals(CatalogProductEventV1.VERSION,
        event.schemaVersion());
      org.junit.jupiter.api.Assertions.assertEquals("PRODUCT_PROFILE_UPDATED", event.eventType());
    }
  }

  @Test
  void outboxFailureHasFiniteRetriesAndDeadTerminalState() throws Exception {
    String target = "/internal/v1/merchant/products/1/price";
    mvc.perform(signed(put(target), "PUT", target)
        .header("X-Merchant-Id", "5")
        .contentType("application/json")
        .content("{\"expectedVersion\":1,\"unitPrice\":109.99}"))
      .andExpect(status().isOk());
    Long id = jdbc.queryForObject("SELECT id FROM catalog_event_outbox", Long.class);

    for (int attempt = 1; attempt <= 5; attempt++) {
      org.junit.jupiter.api.Assertions.assertEquals(1,
        outbox.failed(id, 5, Instant.now(), "inventory unavailable"));
    }

    org.junit.jupiter.api.Assertions.assertEquals("DEAD",
      jdbc.queryForObject("SELECT status FROM catalog_event_outbox WHERE id=?", String.class, id));
    org.junit.jupiter.api.Assertions.assertEquals(5,
      jdbc.queryForObject("SELECT attempts FROM catalog_event_outbox WHERE id=?", Integer.class, id));
  }

  private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder signed(
    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
    String method, String target) {
    String timestamp = Long.toString(Instant.now().getEpochSecond());
    String requestId = UUID.randomUUID().toString();
    return request.header(InternalRequestSignature.SERVICE_HEADER, "commerce-service")
      .header(InternalRequestSignature.TIMESTAMP_HEADER, timestamp)
      .header(InternalRequestSignature.REQUEST_ID_HEADER, requestId)
      .header(InternalRequestSignature.SIGNATURE_HEADER,
        signatures.sign(method, target, timestamp, requestId));
  }
}
