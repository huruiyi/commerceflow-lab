package vip.fairy.commerceflow.commerce.catalog;

import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.commerce.catalog.application.CatalogEventVersionGuard;
import vip.fairy.commerceflow.commerce.catalog.application.CatalogProductEventV1;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CatalogProductEventContractTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void v1FixtureIsCompatibleAndVersionGuardRejectsStaleOrGappedEvents() throws Exception {
    try (InputStream fixture = getClass().getResourceAsStream(
      "/contracts/catalog-product-profile-updated-v1.json")) {
      CatalogProductEventV1 event = mapper.readValue(fixture, CatalogProductEventV1.class);
      event.validate();
      assertEquals(CatalogEventVersionGuard.Decision.APPLY,
        CatalogEventVersionGuard.decide(1, event));
      assertEquals(CatalogEventVersionGuard.Decision.STALE,
        CatalogEventVersionGuard.decide(2, event));
      assertEquals(CatalogEventVersionGuard.Decision.RETRY_VERSION_GAP,
        CatalogEventVersionGuard.decide(0, event));
    }
  }

  @Test
  void unsupportedSchemaVersionIsRejected() throws Exception {
    try (InputStream fixture = getClass().getResourceAsStream(
      "/contracts/catalog-product-profile-updated-v1.json")) {
      CatalogProductEventV1 event = mapper.readValue(fixture, CatalogProductEventV1.class);
      CatalogProductEventV1 unsupported = new CatalogProductEventV1(
        2, event.eventId(), event.eventType(), event.occurredAt(), event.productId(),
        event.merchantId(), event.businessVersion(), event.name(), event.brief(),
        event.description(), event.price(), event.imageUrl(), event.thumbnailUrl(),
        event.lifecycleStatus(), event.locale(), event.correlationId(), event.causationId());
      assertThrows(IllegalArgumentException.class, unsupported::validate);
    }
  }
}
