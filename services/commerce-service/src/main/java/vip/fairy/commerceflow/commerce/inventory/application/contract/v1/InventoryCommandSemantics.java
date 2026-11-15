package vip.fairy.commerceflow.commerce.inventory.application.contract.v1;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.stream.Collectors;

public final class InventoryCommandSemantics {

  private InventoryCommandSemantics() {
  }

  public static String fingerprint(InventoryCommandV1 command) {
    String items = command.items().stream()
      .map(item -> item.productId() + ":" + field(item.productName()) + ":" + item.quantity())
      .collect(Collectors.joining("\n"));
    String semanticPayload = command.commandType() + "\n" + field(command.producer()) + "\n"
      + command.orderId() + "\n" + field(command.reason()) + "\n" + items;
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(semanticPayload.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static String field(String value) {
    return value == null ? "-1:" : value.length() + ":" + value;
  }
}
