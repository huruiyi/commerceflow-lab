package vip.fairy.commerceflow.commerce.architecture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

class OrderServiceAdmissionBaselineTest {

  private static final Path MODULE_ROOT = locateModuleRoot();
  private static final Path JAVA_ROOT = MODULE_ROOT.resolve(
    "src/main/java/vip/fairy/commerceflow/commerce");
  private static final Pattern SQL_TABLE = Pattern.compile(
    "(?im)^\\s*(?:FROM|(?:LEFT\\s+)?JOIN|INSERT\\s+INTO|UPDATE|DELETE\\s+FROM)\\s+([a-z_]+)");

  @Test
  void noNewModuleMayDependDirectlyOnShopOrderMapper() throws IOException {
    Set<String> allowedConsumers = Set.of(
      "fulfillment/application/MerchantFulfillmentService.java",
      "logistics/application/LogisticsNotificationService.java",
      "logistics/application/LogisticsService.java",
      "payment/application/PaymentNotificationService.java",
      "receipt/application/OrderReceiptLifecycleService.java",
      "refund/application/RefundNotificationService.java",
      "refund/application/RefundService.java"
    );
    Set<String> actualConsumers = new HashSet<>();
    try (Stream<Path> sources = Files.walk(JAVA_ROOT)) {
      for (Path source : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
        String relative = JAVA_ROOT.relativize(source).toString().replace('\\', '/');
        if (!relative.startsWith("ordering/") && Files.readString(source).contains(
          "import vip.fairy.commerceflow.commerce.ordering.infrastructure.ShopOrderMapper;")) {
          actualConsumers.add(relative);
        }
      }
    }

    assertTrue(allowedConsumers.containsAll(actualConsumers),
      () -> "New direct ShopOrderMapper consumers require order-service architecture review: "
        + actualConsumers);
  }

  @Test
  void shopOrderMapperMustNotAcquireMoreForeignTableResponsibilities() throws IOException {
    Set<String> ownedTables = Set.of(
      "shop_orders", "order_items", "order_shipping_addresses", "order_events");
    Set<String> allowedForeignTables = Set.of(
      "cart_items", "carts", "shipping_addresses", "payment_merchant_accounts",
      "payment_idempotency_locks", "payment_records", "payment_events",
      "payment_notifications");
    String mapper = Files.readString(MODULE_ROOT.resolve(
      "src/main/resources/mapper/ShopOrderMapper.xml"));
    Set<String> actualForeignTables = new HashSet<>();
    Matcher matcher = SQL_TABLE.matcher(mapper);
    while (matcher.find()) {
      String table = matcher.group(1);
      if (!ownedTables.contains(table)) {
        actualForeignTables.add(table);
      }
    }

    assertTrue(allowedForeignTables.containsAll(actualForeignTables),
      () -> "ShopOrderMapper acquired another module's table: " + actualForeignTables);
  }

  @Test
  void globalTransactionCoordinatorsRemainOutsideTheOrderBoundary() throws IOException {
    String pom = Files.readString(MODULE_ROOT.resolve("pom.xml"));
    StringBuilder sources = new StringBuilder();
    try (Stream<Path> paths = Files.walk(JAVA_ROOT)) {
      for (Path source : paths.filter(path -> path.toString().endsWith(".java")).toList()) {
        sources.append(Files.readString(source));
      }
    }
    String searchable = pom + sources;

    assertFalse(searchable.contains("org.apache.seata") || searchable.contains("io.seata")
        || searchable.contains("dtm-labs") || searchable.contains("@GlobalTransactional")
        || searchable.contains("@TwoPhaseBusinessAction"),
      "Order workflows use explicit local transactions, Outbox/Inbox and business compensation");
  }

  private static Path locateModuleRoot() {
    Path workingDirectory = Path.of("").toAbsolutePath();
    if (Files.isDirectory(workingDirectory.resolve("src/main/java"))) {
      return workingDirectory;
    }
    return workingDirectory.resolve("services/commerce-service");
  }
}
