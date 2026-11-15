package vip.fairy.commerceflow.commerce.inventory.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.commerce.inventory.application.contract.v1.CatalogProductEventTypeV1;
import vip.fairy.commerceflow.commerce.inventory.application.contract.v1.CatalogProductEventV1;
import vip.fairy.commerceflow.commerce.inventory.infrastructure.InventoryCatalogEventInboxMapper;
import vip.fairy.commerceflow.commerce.inventory.infrastructure.InventoryMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Service
public class InventoryCatalogEventHandlerV1 {

  private final InventoryCatalogEventInboxMapper inbox;
  private final InventoryMapper inventory;
  private final ObjectMapper mapper;

  public InventoryCatalogEventHandlerV1(InventoryCatalogEventInboxMapper inbox,
                                        InventoryMapper inventory, ObjectMapper mapper) {
    this.inbox = inbox;
    this.inventory = inventory;
    this.mapper = mapper;
  }

  @Transactional
  public void handlePayload(String payload, String contentHash) {
    if (!matchesPayload(payload, contentHash)) {
      throw new IllegalArgumentException("Catalog event payload hash is invalid");
    }
    try {
      handle(mapper.readValue(payload, CatalogProductEventV1.class));
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Catalog event payload is invalid", exception);
    }
  }

  @Transactional
  public void handle(CatalogProductEventV1 event) {
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
    if (updated != 1) {
      throw new IllegalStateException("Inventory product snapshot changed concurrently");
    }
    if (inbox.complete(event.eventId(), "APPLIED") != 1) {
      throw new IllegalStateException("Inventory catalog event inbox changed concurrently");
    }
  }

  private String fingerprint(CatalogProductEventV1 event) {
    String semanticPayload = event.schemaVersion() + "\n" + field(event.eventType()) + "\n"
      + event.productId() + "\n" + event.merchantId() + "\n" + event.businessVersion() + "\n"
      + field(event.name()) + "\n" + decimal(event.price()) + "\n"
      + field(event.thumbnailUrl()) + "\n" + field(event.lifecycleStatus()) + "\n"
      + field(event.locale());
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(semanticPayload.getBytes(StandardCharsets.UTF_8)));
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

  private boolean matchesPayload(String payload, String contentHash) {
    try {
      String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(payload.getBytes(StandardCharsets.UTF_8)));
      return MessageDigest.isEqual(actual.getBytes(StandardCharsets.UTF_8),
        contentHash.getBytes(StandardCharsets.UTF_8));
    } catch (Exception exception) {
      return false;
    }
  }
}
