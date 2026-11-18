package vip.fairy.commerceflow.fulfillment.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.fulfillment.domain.ShadowKey;
import vip.fairy.commerceflow.fulfillment.domain.ShadowRow;
import vip.fairy.commerceflow.fulfillment.domain.Watermark;
import vip.fairy.commerceflow.fulfillment.infrastructure.ShadowDatabases;
import vip.fairy.commerceflow.fulfillment.infrastructure.FulfillmentMode;
import vip.fairy.commerceflow.fulfillment.infrastructure.ExecutionMapper;
import vip.fairy.commerceflow.fulfillment.infrastructure.ShadowMapper;

@Service
public class ShadowReplication {

  private final ShadowDatabases databases;
  private final ObjectMapper mapper;
  private final FulfillmentMode mode;
  private volatile Report lastReport;

  public ShadowReplication(ShadowDatabases databases, ObjectMapper mapper, FulfillmentMode mode) {
    this.databases = databases;
    this.mapper = mapper;
    this.mode = mode;
  }

  @Scheduled(initialDelayString = "${commerceflow.fulfillment.poll-delay}",
             fixedDelayString = "${commerceflow.fulfillment.poll-delay}")
  public void scheduled() {
    if (mode.shadow()) catchUp(false);
  }

  public synchronized Report catchUp(boolean snapshot) {
    if (!mode.shadow()) throw new IllegalStateException("Shadow replication is closed on this database");
    try (var target = databases.target(); var source = databases.source(); var orders = databases.orders()) {
      if (!"SHADOW".equals(target.getMapper(ExecutionMapper.class).lockReplicationMode())) {
        throw new IllegalStateException("Fulfillment database no longer permits replication");
      }
      ShadowMapper from = source.getMapper(ShadowMapper.class);
      ShadowMapper to = target.getMapper(ShadowMapper.class);
      ShadowMapper owners = orders.getMapper(ShadowMapper.class);
      Watermark prior = to.watermark();
      long head = from.sourceSequence();
      boolean full = snapshot || prior.generation() == 0;
      if (head < prior.sourceSequence()) {
        throw new IllegalStateException("Fulfillment source watermark went backwards");
      }
      if (!full && from.changeCount(prior.sourceSequence(), head) != head - prior.sourceSequence()) {
        throw new IllegalStateException("Fulfillment change log has a gap; a consistent snapshot is required");
      }
      Set<ShadowKey> changes = full ? Set.of() : new HashSet<>(from.changes(prior.sourceSequence(), head));
      ShadowDataset dataset = ShadowDataset.classify(mapper, from.rows(), Set.copyOf(owners.orders()),
        Set.copyOf(owners.ownership()));
      int writes = reconcile(to, dataset.valid(), changes, full);
      reconcileIsolation(to, dataset.isolated(), head);
      List<TableCheck> checks = audit(dataset.valid(), index(to.rows()));
      Watermark applied = new Watermark(prior.generation() + (full ? 1 : 0), head);
      to.advance(applied);
      target.commit();
      lastReport = new Report(applied, full ? "SNAPSHOT" : "INCREMENTAL", changes.size(), writes,
        dataset.isolated().size(), checks);
      return lastReport;
    } catch (RuntimeException failure) {
      lastReport = null;
      throw failure;
    }
  }

  public Report lastReport() {
    return lastReport;
  }

  public CommandStatus query(UUID commandId, long merchantId) {
    try (var target = databases.target()) {
      ShadowRow row = target.getMapper(ShadowMapper.class).command(commandId.toString());
      if (row == null) return null;
      Map<String, Object> values = ShadowDataset.values(mapper, row);
      if (((Number) values.get("merchant_id")).longValue() != merchantId) return null;
      String operation = row.tableName().equals(ShadowDataset.SHIPMENTS) ? "SHIP" : (String) values.get("operation");
      String status = (String) values.get("status");
      Long version = null;
      if ("COMPLETED".equals(status)) {
        if ("SHIP".equals(operation)) version = 0L;
        if ("UPDATE_SHIPMENT".equals(operation)) version = ((Number) values.get("expected_version")).longValue() + 1;
      }
      return new CommandStatus(1, commandId, ((Number) values.get("order_id")).longValue(), operation,
        (String) values.get("step"), status, version, (String) values.get("last_error"), true);
    }
  }

  private int reconcile(ShadowMapper target, Map<ShadowKey, ShadowRow> desired, Set<ShadowKey> changes,
                        boolean full) {
    Map<ShadowKey, ShadowRow> existing = index(target.rows());
    int writes = 0;
    for (String table : ShadowDataset.TABLES.reversed()) {
      for (ShadowKey key : existing.keySet()) {
        if (key.tableName().equals(table) && !desired.containsKey(key)) writes += target.delete(key);
      }
    }
    for (String table : ShadowDataset.TABLES) {
      for (ShadowRow row : desired.values()) {
        if (!row.tableName().equals(table)) continue;
        ShadowRow old = existing.get(row.key());
        if ((full || old == null || changes.contains(row.key())) && !row.equals(old)) {
          target.upsert(table, ShadowDataset.values(mapper, row));
          writes++;
        }
      }
    }
    return writes;
  }

  private void reconcileIsolation(ShadowMapper target, List<ShadowDataset.Isolated> isolated, long head) {
    Set<ShadowKey> invalid = new HashSet<>();
    for (var entry : isolated) {
      invalid.add(entry.row().key());
      target.isolate(entry.row(), head, entry.reason());
    }
    for (ShadowRow prior : target.quarantine()) {
      if (!invalid.contains(prior.key())) target.clearIsolation(prior.key());
    }
  }

  private Map<ShadowKey, ShadowRow> index(List<ShadowRow> rows) {
    Map<ShadowKey, ShadowRow> indexed = new HashMap<>();
    for (ShadowRow row : rows) indexed.put(row.key(), row);
    return indexed;
  }

  private List<TableCheck> audit(Map<ShadowKey, ShadowRow> expected, Map<ShadowKey, ShadowRow> actual) {
    List<TableCheck> checks = new ArrayList<>();
    for (String table : ShadowDataset.TABLES) {
      List<ShadowRow> source = tableRows(expected, table);
      List<ShadowRow> target = tableRows(actual, table);
      if (!source.equals(target)) throw new IllegalStateException("Fulfillment shadow row mismatch for " + table);
      checks.add(new TableCheck(table, source.size(), target.size(), fingerprint(source)));
    }
    return List.copyOf(checks);
  }

  private List<ShadowRow> tableRows(Map<ShadowKey, ShadowRow> rows, String table) {
    return rows.values().stream().filter(row -> row.tableName().equals(table))
      .sorted(Comparator.comparing(ShadowRow::rowKey)).toList();
  }

  private String fingerprint(List<ShadowRow> rows) {
    try {
      MessageDigest hash = MessageDigest.getInstance("SHA-256");
      for (ShadowRow row : rows) {
        hash.update(row.payload().getBytes(StandardCharsets.UTF_8));
        hash.update((byte) '\n');
      }
      return HexFormat.of().formatHex(hash.digest());
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 unavailable", exception);
    }
  }

  public record Report(Watermark watermark, String mode, int changedKeys, int appliedRows,
                       int openOrphans, List<TableCheck> tables) {
  }

  public record TableCheck(String tableName, int sourceRows, int shadowRows, String sha256) {
  }

  public record CommandStatus(int version, UUID commandId, long orderId, String operation, String step,
                              String status, Long shipmentVersion, String errorCode, boolean shadow) {
  }
}
