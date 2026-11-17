package vip.fairy.commerceflow.inventory.api.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.inventory.application.service.MerchantInventoryService;
import vip.fairy.commerceflow.inventory.domain.InventoryMovementView;
import vip.fairy.commerceflow.inventory.domain.MerchantInventoryItem;
import vip.fairy.commerceflow.inventory.infrastructure.transport.InventoryTrafficGate;

@RestController
@RequestMapping("/internal/v1/inventory/merchants/{merchantId}")
public class MerchantInventoryHttpAdapter {

  private final MerchantInventoryService inventory;
  private final InventoryTrafficGate trafficGate;
  private final ObjectMapper mapper;

  public MerchantInventoryHttpAdapter(MerchantInventoryService inventory,
                                      InventoryTrafficGate trafficGate,
                                      ObjectMapper mapper) {
    this.inventory = inventory;
    this.trafficGate = trafficGate;
    this.mapper = mapper;
  }

  @GetMapping("/items")
  List<MerchantInventoryItem> list(@PathVariable Long merchantId) {
    trafficGate.requireOpen();
    return inventory.list(merchantId);
  }

  @GetMapping("/items/{productId}/movements")
  List<InventoryMovementView> movements(@PathVariable Long merchantId,
                                        @PathVariable Long productId) {
    trafficGate.requireOpen();
    return inventory.movements(merchantId, productId);
  }

  @PostMapping("/items/{productId}/adjustments")
  MerchantInventoryItem adjust(@PathVariable Long merchantId,
                               @PathVariable Long productId,
                               @RequestBody String payload,
                               HttpServletRequest request) {
    trafficGate.requireOpen();
    requirePayloadHash(payload, request.getHeader("X-CommerceFlow-Content-SHA256"));
    try {
      AdjustmentRequest adjustment = mapper.readValue(payload, AdjustmentRequest.class);
      adjustment.validate();
      return inventory.adjust(merchantId, productId, adjustment.delta(), adjustment.reason());
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Inventory adjustment payload is invalid", exception);
    }
  }

  private void requirePayloadHash(String payload, String contentHash) {
    try {
      String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(payload.getBytes(StandardCharsets.UTF_8)));
      if (!actual.equals(contentHash)) {
        throw new IllegalArgumentException("Inventory adjustment payload hash is invalid");
      }
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  public record AdjustmentRequest(
    @NotNull @Min(-100000) @Max(100000) Integer delta,
    @NotBlank @Size(max = 300) String reason
  ) {

    void validate() {
      if (delta == null || delta < -100000 || delta > 100000
          || reason == null || reason.isBlank() || reason.length() > 300) {
        throw new IllegalArgumentException("Inventory adjustment payload is invalid");
      }
    }
  }
}
