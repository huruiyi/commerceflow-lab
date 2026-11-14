package vip.fairy.commerceflow.commerce.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MicroserviceReadinessBaselineTest {

  private static final Path MODULE_ROOT = locateModuleRoot();
  private static final Path JAVA_ROOT = MODULE_ROOT.resolve(
    "src/main/java/vip/fairy/commerceflow/commerce");
  private static final Path MIGRATION_ROOT = MODULE_ROOT.resolve("src/main/resources/db/migration");
  private static final Pattern PACKAGE = Pattern.compile(
    "package\\s+vip\\.fairy\\.commerceflow\\.commerce\\.([a-z]+)");
  private static final Pattern IMPORT = Pattern.compile(
    "import\\s+vip\\.fairy\\.commerceflow\\.commerce\\.([a-z]+)\\.([^;]+);");
  private static final Pattern CREATE_TABLE = Pattern.compile(
    "(?im)^\\s*CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?`?([a-z0-9_]+)`?");

  @Test
  void newTablesMustDeclareAnOwner() throws IOException {
    Set<String> actualTables = new HashSet<>();
    try (Stream<Path> migrations = Files.list(MIGRATION_ROOT)) {
      for (Path migration : migrations.filter(path -> path.toString().endsWith(".sql")).toList()) {
        Matcher matcher = CREATE_TABLE.matcher(Files.readString(migration));
        while (matcher.find()) {
          actualTables.add(matcher.group(1));
        }
      }
    }

    assertEquals(tableOwners().keySet(), actualTables,
      "Every commerce table must have exactly one audited module owner");
  }

  @Test
  void crossModuleDependenciesMustNotExceedTheAuditedBaseline() throws IOException {
    Map<String, DependencyCount> actual = new HashMap<>();
    try (Stream<Path> sources = Files.walk(JAVA_ROOT)) {
      for (Path source : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
        String content = Files.readString(source);
        Matcher packageMatcher = PACKAGE.matcher(content);
        if (!packageMatcher.find()) {
          continue;
        }
        String sourceModule = packageMatcher.group(1);
        Matcher importMatcher = IMPORT.matcher(content);
        while (importMatcher.find()) {
          String targetModule = importMatcher.group(1);
          if (sourceModule.equals(targetModule) || "shared".equals(targetModule)) {
            continue;
          }
          String key = sourceModule + ">" + targetModule;
          DependencyCount count = actual.computeIfAbsent(key, ignored -> new DependencyCount());
          count.references++;
          if (importMatcher.group(2).startsWith("infrastructure.")) {
            count.infrastructureReferences++;
          }
        }
      }
    }

    Map<String, DependencyLimit> baseline = dependencyBaseline();
    for (Map.Entry<String, DependencyCount> entry : actual.entrySet()) {
      DependencyLimit limit = baseline.get(entry.getKey());
      assertNotNull(limit, () -> "New cross-module dependency requires architecture review: " + entry.getKey());
      assertTrue(entry.getValue().references <= limit.references,
        () -> entry.getKey() + " exceeds its audited dependency limit");
      assertTrue(entry.getValue().infrastructureReferences <= limit.infrastructureReferences,
        () -> entry.getKey() + " adds a direct dependency on another module's infrastructure");
    }
  }

  private static Map<String, String> tableOwners() {
    return Map.ofEntries(
      Map.entry("app_users", "identity"),
      Map.entry("roles", "identity"),
      Map.entry("user_roles", "identity"),
      Map.entry("persistent_logins", "identity"),
      Map.entry("products", "catalog"),
      Map.entry("product_translations", "catalog"),
      Map.entry("carts", "cart"),
      Map.entry("cart_items", "cart"),
      Map.entry("customer_orders", "cart"),
      Map.entry("shipping_addresses", "customer"),
      Map.entry("shop_orders", "ordering"),
      Map.entry("order_items", "ordering"),
      Map.entry("order_shipping_addresses", "ordering"),
      Map.entry("order_events", "ordering"),
      Map.entry("payment_records", "payment"),
      Map.entry("payment_merchant_accounts", "payment"),
      Map.entry("payment_notifications", "payment"),
      Map.entry("payment_events", "payment"),
      Map.entry("payment_idempotency_locks", "payment"),
      Map.entry("payment_reconciliation_issue", "payment"),
      Map.entry("payment_outbox", "payment"),
      Map.entry("kafka_consumed_event", "payment"),
      Map.entry("refund_records", "refund"),
      Map.entry("refund_events", "refund"),
      Map.entry("refund_notifications", "refund"),
      Map.entry("shipment_records", "logistics"),
      Map.entry("shipment_events", "logistics"),
      Map.entry("shipment_notifications", "logistics"),
      Map.entry("inventory_reservations", "inventory"),
      Map.entry("inventory_movements", "inventory"),
      Map.entry("product_reviews", "review"),
      Map.entry("customer_notifications", "notification"),
      Map.entry("notification_inbox", "notification"),
      Map.entry("scheduled_task_policies", "scheduling"),
      Map.entry("scheduled_tasks", "scheduling"),
      Map.entry("scheduled_task_events", "scheduling"),
      Map.entry("scheduled_task_policy_events", "scheduling"),
      Map.entry("shedlock", "scheduling")
    );
  }

  private static Path locateModuleRoot() {
    Path workingDirectory = Path.of("").toAbsolutePath();
    if (Files.isDirectory(workingDirectory.resolve("src/main/java"))) {
      return workingDirectory;
    }
    Path repositoryModule = workingDirectory.resolve("services/commerce-service");
    if (Files.isDirectory(repositoryModule.resolve("src/main/java"))) {
      return repositoryModule;
    }
    throw new IllegalStateException("Cannot locate the commerce-service module from " + workingDirectory);
  }

  private static Map<String, DependencyLimit> dependencyBaseline() {
    return Map.ofEntries(
      dependency("cart", "catalog", 5, 2),
      dependency("cart", "identity", 5, 0),
      dependency("catalog", "review", 3, 1),
      dependency("customer", "cart", 1, 1),
      dependency("customer", "identity", 2, 0),
      dependency("fulfillment", "identity", 2, 0),
      dependency("fulfillment", "logistics", 3, 1),
      dependency("fulfillment", "ordering", 7, 1),
      dependency("fulfillment", "refund", 3, 1),
      dependency("inventory", "cart", 1, 0),
      dependency("inventory", "catalog", 2, 0),
      dependency("inventory", "identity", 2, 0),
      dependency("inventory", "notification", 2, 0),
      dependency("logistics", "identity", 1, 0),
      dependency("logistics", "ordering", 2, 2),
      dependency("logistics", "receipt", 1, 0),
      dependency("notification", "identity", 2, 0),
      dependency("ordering", "cart", 5, 1),
      dependency("ordering", "customer", 2, 0),
      dependency("ordering", "identity", 4, 0),
      dependency("ordering", "inventory", 2, 0),
      dependency("ordering", "logistics", 4, 0),
      dependency("ordering", "payment", 16, 2),
      dependency("ordering", "refund", 3, 1),
      dependency("ordering", "scheduling", 4, 1),
      dependency("payment", "inventory", 1, 0),
      dependency("payment", "ordering", 2, 1),
      dependency("payment", "refund", 1, 0),
      dependency("receipt", "ordering", 3, 2),
      dependency("receipt", "scheduling", 3, 1),
      dependency("refund", "identity", 2, 0),
      dependency("refund", "inventory", 1, 0),
      dependency("refund", "ordering", 3, 2),
      dependency("refund", "payment", 8, 1),
      dependency("review", "catalog", 1, 0),
      dependency("review", "identity", 2, 0),
      dependency("review", "ordering", 3, 0),
      dependency("review", "receipt", 1, 0),
      dependency("scheduling", "ordering", 1, 0),
      dependency("scheduling", "receipt", 2, 0),
      dependency("shared", "identity", 1, 0)
    );
  }

  private static Map.Entry<String, DependencyLimit> dependency(
    String source, String target, int references, int infrastructureReferences
  ) {
    return Map.entry(source + ">" + target,
      new DependencyLimit(references, infrastructureReferences));
  }

  private record DependencyLimit(int references, int infrastructureReferences) {
  }

  private static final class DependencyCount {

    private int references;
    private int infrastructureReferences;
  }
}
