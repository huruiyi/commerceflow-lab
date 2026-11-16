package vip.fairy.commerceflow.inventory.api.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vip.fairy.commerceflow.inventory.application.contract.v1.InventoryCommandResultV1;
import vip.fairy.commerceflow.inventory.application.contract.v1.InventoryCommandV1;
import vip.fairy.commerceflow.inventory.application.port.InventoryCommandUseCase;
import vip.fairy.commerceflow.inventory.infrastructure.transport.InventoryTrafficGate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/internal/v1/inventory/commands")
public class InventoryCommandHttpAdapter {

  private final InventoryTrafficGate trafficGate;
  private final InventoryCommandUseCase commands;
  private final ObjectMapper mapper;

  public InventoryCommandHttpAdapter(InventoryTrafficGate trafficGate,
                                     InventoryCommandUseCase commands,
                                     ObjectMapper mapper) {
    this.trafficGate = trafficGate;
    this.commands = commands;
    this.mapper = mapper;
  }

  @PostMapping
  InventoryCommandResultV1 handle(@RequestBody String payload, HttpServletRequest request) {
    trafficGate.requireOpen();
    String contentHash = request.getHeader("X-CommerceFlow-Content-SHA256");
    if (!hash(payload).equals(contentHash)) {
      throw new IllegalArgumentException("Inventory command payload hash is invalid");
    }
    try {
      return commands.handle(mapper.readValue(payload, InventoryCommandV1.class));
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Inventory command payload is invalid", exception);
    }
  }

  private String hash(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }
}
