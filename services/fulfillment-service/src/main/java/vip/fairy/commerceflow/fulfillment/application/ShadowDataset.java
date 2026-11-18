package vip.fairy.commerceflow.fulfillment.application;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.fulfillment.domain.OrderMerchant;
import vip.fairy.commerceflow.fulfillment.domain.ShadowKey;
import vip.fairy.commerceflow.fulfillment.domain.ShadowRow;

record ShadowDataset(Map<ShadowKey, ShadowRow> valid, List<Isolated> isolated) {

  static final String LOCKS = "fulfillment_order_locks";
  static final String COMMANDS = "fulfillment_command_ids";
  static final String SHIPMENTS = "fulfillment_shipment_jobs";
  static final String MERCHANT = "fulfillment_merchant_jobs";
  static final String SHIPMENT_RECOVERY = "fulfillment_shipment_recoveries";
  static final String MERCHANT_RECOVERY = "fulfillment_merchant_recoveries";
  static final List<String> TABLES = List.of(LOCKS, COMMANDS, SHIPMENTS, MERCHANT,
    SHIPMENT_RECOVERY, MERCHANT_RECOVERY);
  private static final TypeReference<Map<String, Object>> VALUES = new TypeReference<>() { };

  static Map<String, Object> values(ObjectMapper mapper, ShadowRow row) {
    return mapper.readValue(row.payload(), VALUES);
  }

  static ShadowDataset classify(ObjectMapper mapper, List<ShadowRow> rows, Set<Long> orders,
                                Set<OrderMerchant> ownership) {
    Map<String, Parsed> registrations = new HashMap<>();
    Map<String, Parsed> validJobs = new HashMap<>();
    Set<String> commandIds = new HashSet<>();
    Set<String> duplicates = new HashSet<>();
    List<Parsed> parsed = rows.stream().map(row -> new Parsed(row, values(mapper, row))).toList();
    for (Parsed row : parsed) {
      String table = row.row().tableName();
      if (table.equals(COMMANDS)) registrations.put(row.command(), row);
      if (isJob(table) && !commandIds.add(row.command())) duplicates.add(row.command());
    }
    for (Parsed row : parsed) {
      if (isJob(row.row().tableName()) && validJob(row, registrations, duplicates, ownership)) {
        validJobs.put(row.command(), row);
      }
    }
    Map<ShadowKey, ShadowRow> valid = new HashMap<>();
    List<Isolated> isolated = new ArrayList<>();
    for (Parsed row : parsed) {
      String reason = switch (row.row().tableName()) {
        case LOCKS -> orders.contains(row.order()) ? null : "ORDER_MISSING";
        case SHIPMENTS, MERCHANT -> validJobs.containsKey(row.command()) ? null : "JOB_REGISTRY_OR_OWNERSHIP";
        case COMMANDS -> validJobs.containsKey(row.command()) ? null : "JOB_MISSING_OR_ISOLATED";
        case SHIPMENT_RECOVERY, MERCHANT_RECOVERY -> validRecovery(row, validJobs) ? null : "RECOVERY_JOB_MISSING";
        default -> throw new IllegalStateException("Unknown fulfillment shadow table");
      };
      if (reason == null) valid.put(row.row().key(), row.row());
      else isolated.add(new Isolated(row.row(), reason));
    }
    return new ShadowDataset(valid, isolated);
  }

  private static boolean isJob(String table) {
    return table.equals(SHIPMENTS) || table.equals(MERCHANT);
  }

  private static boolean validJob(Parsed row, Map<String, Parsed> registrations, Set<String> duplicates,
                                  Set<OrderMerchant> ownership) {
    Parsed registration = registrations.get(row.command());
    return registration != null && !duplicates.contains(row.command()) && row.order() == registration.order()
      && ownership.contains(new OrderMerchant(row.order(), row.number("merchant_id")));
  }

  private static boolean validRecovery(Parsed row, Map<String, Parsed> jobs) {
    Parsed job = jobs.get(row.command());
    if (job == null) return false;
    if (row.row().tableName().equals(MERCHANT_RECOVERY)) return job.row().tableName().equals(MERCHANT);
    return job.row().tableName().equals(SHIPMENTS) && row.order() == job.order();
  }

  record Isolated(ShadowRow row, String reason) {
  }

  private record Parsed(ShadowRow row, Map<String, Object> values) {

    String command() {
      return (String) values.get("command_id");
    }

    long order() {
      return number("order_id");
    }

    long number(String column) {
      return ((Number) values.get(column)).longValue();
    }
  }
}
