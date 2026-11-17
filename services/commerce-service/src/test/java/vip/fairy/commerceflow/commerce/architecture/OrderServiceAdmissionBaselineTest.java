package vip.fairy.commerceflow.commerce.architecture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

class OrderServiceAdmissionBaselineTest {

  private static final Path MODULE_ROOT = locateModuleRoot();
  private static final Path JAVA_ROOT = MODULE_ROOT.resolve(
    "src/main/java/vip/fairy/commerceflow/commerce");
  private static final Pattern ORDER_TABLE = Pattern.compile(
    "(?i)\\b(shop_orders|order_items|order_shipping_addresses|order_events|"
      + "order_process_managers|order_command_outbox|order_result_inbox)\\b");

  @Test
  void commerceMappersMustNotAccessOrderOwnedTablesAfterCutover() throws IOException {
    Path mapperRoot = MODULE_ROOT.resolve("src/main/resources/mapper");
    try (Stream<Path> mappers = Files.walk(mapperRoot)) {
      for (Path mapper : mappers.filter(path -> path.toString().endsWith(".xml")).toList()) {
        assertFalse(ORDER_TABLE.matcher(Files.readString(mapper)).find(),
          () -> "Commerce mapper still accesses order-owned data: " + mapper.getFileName());
      }
    }
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
