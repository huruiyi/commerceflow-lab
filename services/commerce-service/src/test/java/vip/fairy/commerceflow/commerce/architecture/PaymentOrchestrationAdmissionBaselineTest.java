package vip.fairy.commerceflow.commerce.architecture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

class PaymentOrchestrationAdmissionBaselineTest {

  private static final Path MODULE_ROOT = locateModuleRoot();
  private static final Path JAVA_ROOT = MODULE_ROOT.resolve(
    "src/main/java/vip/fairy/commerceflow/commerce");
  private static final Pattern OWNED_TABLE = Pattern.compile(
    "(?i)\\b(payment_records|payment_merchant_accounts|payment_notifications|payment_events|"
      + "payment_idempotency_locks|payment_reconciliation_issue|payment_outbox|"
      + "kafka_consumed_event|payment_process_managers|payment_command_outbox|"
      + "payment_result_inbox|refund_records|refund_events|refund_notifications)\\b");
  private static final Pattern FOREIGN_TABLE = Pattern.compile(
    "(?i)\\b(app_users|shop_orders|order_items|order_shipping_addresses|order_events|"
      + "product_inventory|inventory_reservations|shipment_records|scheduled_tasks)\\b");
  private static final Pattern INTERNAL_IMPORT = Pattern.compile(
    "import vip\\.fairy\\.commerceflow\\.commerce\\.(refund\\.|payment\\.(api\\.|domain\\.|"
      + "infrastructure\\.|application\\.(?!contract\\.v1\\.)))");

  @Test
  void paymentOwnedTablesStayInsidePaymentAndRefundMappers() throws IOException {
    Path mapperRoot = MODULE_ROOT.resolve("src/main/resources/mapper");
    try (Stream<Path> mappers = Files.walk(mapperRoot)) {
      for (Path mapper : mappers.filter(path -> path.toString().endsWith(".xml")).toList()) {
        String content = Files.readString(mapper);
        if (OWNED_TABLE.matcher(content).find()) {
          assertTrue(content.contains(".payment.") || content.contains(".refund."),
            () -> "Foreign mapper accesses payment-orchestration data: " + mapper.getFileName());
        }
        if (content.contains(".payment.") || content.contains(".refund.")) {
          assertFalse(FOREIGN_TABLE.matcher(content).find(),
            () -> "Payment-orchestration mapper accesses a foreign table: " + mapper.getFileName());
        }
      }
    }
  }

  @Test
  void externalConsumersUseOnlyPaymentV1ContractsAndOrderingPorts() throws IOException {
    try (Stream<Path> sources = Files.walk(JAVA_ROOT)) {
      Set<String> consumers = sources.filter(path -> path.toString().endsWith(".java"))
        .filter(path -> !isPaymentOrRefund(path))
        .filter(path -> INTERNAL_IMPORT.matcher(read(path)).find())
        .map(path -> JAVA_ROOT.relativize(path).toString().replace('\\', '/'))
        .collect(java.util.stream.Collectors.toSet());
      assertTrue(consumers.isEmpty(),
        () -> "External code must use Payment V1 contracts and consumer-owned ports: " + consumers);
    }
  }

  @Test
  void globalTransactionCoordinatorsRemainAbsent() throws IOException {
    StringBuilder searchable = new StringBuilder(Files.readString(MODULE_ROOT.resolve("pom.xml")));
    try (Stream<Path> sources = Files.walk(JAVA_ROOT)) {
      for (Path source : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
        searchable.append(Files.readString(source));
      }
    }
    String content = searchable.toString();
    assertFalse(content.contains("org.apache.seata") || content.contains("io.seata")
        || content.contains("dtm-labs") || content.contains("@GlobalTransactional")
        || content.contains("@TwoPhaseBusinessAction"),
      "Payment orchestration must use local transactions and explicit recovery protocols");
  }

  private static boolean isPaymentOrRefund(Path path) {
    String relative = JAVA_ROOT.relativize(path).toString().replace('\\', '/');
    return relative.startsWith("payment/") || relative.startsWith("refund/");
  }

  private static String read(Path path) {
    try {
      return Files.readString(path);
    } catch (IOException exception) {
      throw new IllegalStateException("Could not read " + path, exception);
    }
  }

  private static Path locateModuleRoot() {
    Path workingDirectory = Path.of("").toAbsolutePath();
    if (Files.isDirectory(workingDirectory.resolve("src/main/java"))) {
      return workingDirectory;
    }
    return workingDirectory.resolve("services/commerce-service");
  }
}
