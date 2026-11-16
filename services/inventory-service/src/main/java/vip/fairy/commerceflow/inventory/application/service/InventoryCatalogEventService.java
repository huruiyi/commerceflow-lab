package vip.fairy.commerceflow.inventory.application.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.inventory.application.contract.v1.CatalogProductEventTypeV1;
import vip.fairy.commerceflow.inventory.application.contract.v1.CatalogProductEventV1;
import vip.fairy.commerceflow.inventory.infrastructure.persistence.InventoryCatalogEventInboxMapper;
import vip.fairy.commerceflow.inventory.infrastructure.persistence.InventoryMapper;

@Service
public class InventoryCatalogEventService {

  private final InventoryCatalogEventInboxMapper inbox;
  private final InventoryMapper inventory;
  private final ObjectMapper mapper;

  public InventoryCatalogEventService(InventoryCatalogEventInboxMapper inbox,
                                      InventoryMapper inventory, ObjectMapper mapper) {
    this.inbox = inbox;
    this.inventory = inventory;
    this.mapper = mapper;
  }

  @Transactional
  public void handlePayload(String payload, String contentHash) {
    if (!hash(payload).equals(contentHash)) {
      throw new IllegalArgumentException("Catalog event payload hash is invalid");
    }
    try {
      handle(mapper.readValue(payload, CatalogProductEventV1.class));
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Catalog event payload is invalid", exception);
    }
  }

  private void handle(CatalogProductEventV1 event) {
    CatalogProductEventTypeV1 type = event.validatedType();
    String fingerprint = fingerprint(event);
    if (inbox.claim(event.eventId(), event.productId(), event.businessVersion(), fingerprint) != 1) {
      if (!fingerprint.equals(inbox.payloadFingerprint(event.eventId()))) {
        throw new IllegalArgumentException("Catalog eventId was reused with a different payload");
      }
      return;
    }
    Long currentVersion = inventory.catalogBusinessVersion(event.productId());
    if (currentVersion == null) {
      throw new IllegalStateException("Inventory product snapshot does not exist");
    }
    if (event.businessVersion() <= currentVersion) {
      inbox.complete(event.eventId(), "STALE");
      return;
    }
    if (event.businessVersion() != currentVersion + 1) {
      throw new IllegalStateException("Inventory catalog event has a businessVersion gap");
    }
    int updated = type == CatalogProductEventTypeV1.PRODUCT_TRANSLATION_UPDATED
      ? inventory.advanceCatalogVersion(event.productId(), event.businessVersion())
      : inventory.updateCatalogSnapshot(event.productId(), event.merchantId(), event.name(),
        event.thumbnailUrl(), event.price(), event.lifecycleStatus(), event.businessVersion());
    if (updated != 1 || inbox.complete(event.eventId(), "APPLIED") != 1) {
      throw new IllegalStateException("Inventory catalog projection changed concurrently");
    }
  }

  private String fingerprint(CatalogProductEventV1 event) {
    String semanticPayload = event.schemaVersion() + "\n" + field(event.eventType()) + "\n"
      + event.productId() + "\n" + event.merchantId() + "\n" + event.businessVersion() + "\n"
      + field(event.name()) + "\n" + decimal(event.price()) + "\n"
      + field(event.thumbnailUrl()) + "\n" + field(event.lifecycleStatus()) + "\n"
      + field(event.locale());
    return hash(semanticPayload);
  }

  private String hash(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private String decimal(BigDecimal value) {
    return value == null ? "-1:" : field(value.stripTrailingZeros().toPlainString());
  }

  private String field(String value) {
    return value == null ? "-1:" : value.length() + ":" + value;
  }
}
