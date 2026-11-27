package vip.fairy.commerceflow.order.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.order.domain.ShadowTable;
import vip.fairy.commerceflow.order.infrastructure.OrderShadowProperties;
import vip.fairy.commerceflow.order.infrastructure.SourceConnectionFactory;

@Service
public class ShadowReplicationService {

  private static final String REPLICA = "commerce-order-v1";

  private final DataSource target;
  private final SourceConnectionFactory sourceFactory;
  private final OrderShadowProperties properties;
  private final ObjectMapper mapper;

  public ShadowReplicationService(DataSource target, SourceConnectionFactory sourceFactory,
                                  OrderShadowProperties properties, ObjectMapper mapper) {
    this.target = target;
    this.sourceFactory = sourceFactory;
    this.properties = properties;
    this.mapper = mapper;
  }

  @Scheduled(initialDelayString = "${commerceflow.order.shadow.initial-delay:2s}",
    fixedDelayString = "${commerceflow.order.shadow.fixed-delay:2s}")
  public void replicate() {
    if (!properties.shadow().enabled()) return;
    try {
      if (watermark().generation() == 0) snapshot();
      catchUp();
      replayQuarantine();
    } catch (SQLException exception) {
      throw new IllegalStateException("Order shadow replication failed", exception);
    }
  }

  public synchronized ReplicationStatus snapshot() throws SQLException {
    try (Connection source = sourceFactory.open(); Connection targetConnection = target.getConnection()) {
      source.setAutoCommit(false);
      source.setReadOnly(true);
      source.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      long sequence = scalar(source, "SELECT last_sequence FROM order_shadow_change_clock WHERE singleton_id=1");
      Map<ShadowTable,List<Map<String,Object>>> rows = new LinkedHashMap<>();
      for (ShadowTable table : ShadowTable.ALL) rows.put(table, rows(source, table, null));

      targetConnection.setAutoCommit(false);
      try {
        List<ShadowTable> reverse = new ArrayList<>(ShadowTable.ALL);
        java.util.Collections.reverse(reverse);
        for (ShadowTable table : reverse) execute(targetConnection, "DELETE FROM " + table.name());
        for (var entry : rows.entrySet()) {
          for (Map<String,Object> row : entry.getValue()) upsert(targetConnection, entry.getKey(), row);
        }
        try (PreparedStatement statement = targetConnection.prepareStatement(
          "UPDATE order_shadow_watermark SET snapshot_generation=snapshot_generation+1," +
            "source_sequence=?,snapshot_completed_at=CURRENT_TIMESTAMP(6),caught_up_at=CURRENT_TIMESTAMP(6) " +
            "WHERE replica_name=?")) {
          statement.setLong(1, sequence);
          statement.setString(2, REPLICA);
          statement.executeUpdate();
        }
        targetConnection.commit();
        source.commit();
        return watermark(targetConnection);
      } catch (Exception exception) {
        targetConnection.rollback();
        source.rollback();
        throw exception;
      }
    }
  }

  public synchronized ReplicationStatus catchUp() throws SQLException {
    ReplicationStatus state = watermark();
    try (Connection source = sourceFactory.open(); Connection targetConnection = target.getConnection()) {
      source.setAutoCommit(false);
      source.setReadOnly(true);
      source.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      long ceiling = scalar(source, "SELECT last_sequence FROM order_shadow_change_clock WHERE singleton_id=1");
      List<Change> changes = changes(source, state.sourceSequence(), ceiling);
      targetConnection.setAutoCommit(false);
      long applied = state.sourceSequence();
      try {
        for (Change change : changes) {
          ShadowTable table = ShadowTable.named(change.tableName());
          Map<String,Object> row = one(source, table, change.rowKey());
          try {
            if (change.operation().equals("DELETE") || row == null) delete(targetConnection, table, change.rowKey());
            else upsert(targetConnection, table, row);
          } catch (SQLException exception) {
            if (exception.getErrorCode() != 1452) throw exception;
            quarantine(targetConnection, change, row, exception.getMessage());
          }
          applied = change.sequence();
        }
        updateWatermark(targetConnection, applied, applied == ceiling);
        targetConnection.commit();
        source.commit();
        return watermark(targetConnection);
      } catch (Exception exception) {
        targetConnection.rollback();
        source.rollback();
        throw exception;
      }
    }
  }

  @Scheduled(initialDelayString = "${commerceflow.order.shadow.audit-delay:30s}",
    fixedDelayString = "${commerceflow.order.shadow.audit-delay:30s}")
  public void scheduledAudit() {
    if (!properties.shadow().enabled()) return;
    try {
      audit();
    } catch (SQLException exception) {
      throw new IllegalStateException("Order shadow audit failed", exception);
    }
  }

  public synchronized List<TableAudit> audit() throws SQLException {
    try (Connection source = sourceFactory.open(); Connection targetConnection = target.getConnection()) {
      source.setAutoCommit(false);
      source.setReadOnly(true);
      source.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      long sequence = scalar(source, "SELECT last_sequence FROM order_shadow_change_clock WHERE singleton_id=1");
      List<TableAudit> result = new ArrayList<>();
      for (ShadowTable table : ShadowTable.ALL) {
        List<Map<String,Object>> sourceRows = rows(source, table, null);
        List<Map<String,Object>> targetRows = rows(targetConnection, table, null);
        String sourceFingerprint = fingerprint(table, sourceRows);
        String targetFingerprint = fingerprint(table, targetRows);
        String status = sourceRows.size() == targetRows.size() && sourceFingerprint.equals(targetFingerprint)
          ? "MATCH" : "MISMATCH";
        try (PreparedStatement statement = targetConnection.prepareStatement(
          "INSERT INTO order_shadow_audit(source_sequence,table_name,source_count,target_count," +
            "source_fingerprint,target_fingerprint,status) VALUES (?,?,?,?,?,?,?)")) {
          statement.setLong(1, sequence);
          statement.setString(2, table.name());
          statement.setLong(3, sourceRows.size());
          statement.setLong(4, targetRows.size());
          statement.setString(5, sourceFingerprint);
          statement.setString(6, targetFingerprint);
          statement.setString(7, status);
          statement.executeUpdate();
        }
        result.add(new TableAudit(table.name(), sourceRows.size(), targetRows.size(), status));
      }
      source.commit();
      return result;
    }
  }

  public ReplicationStatus watermark() throws SQLException {
    try (Connection connection = target.getConnection()) {
      return watermark(connection);
    }
  }

  private ReplicationStatus watermark(Connection connection) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(
      "SELECT snapshot_generation,source_sequence,snapshot_completed_at,caught_up_at " +
        "FROM order_shadow_watermark WHERE replica_name=?")) {
      statement.setString(1, REPLICA);
      try (ResultSet result = statement.executeQuery()) {
        result.next();
        return new ReplicationStatus(result.getLong(1), result.getLong(2),
          instant(result, 3), instant(result, 4));
      }
    }
  }

  private List<Change> changes(Connection connection, long after, long ceiling) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(
      "SELECT change_sequence,table_name,row_key,operation FROM order_shadow_change_log " +
        "WHERE change_sequence>? AND change_sequence<=? ORDER BY change_sequence LIMIT ?")) {
      statement.setLong(1, after);
      statement.setLong(2, ceiling);
      statement.setInt(3, properties.shadow().batchSize());
      try (ResultSet result = statement.executeQuery()) {
        List<Change> changes = new ArrayList<>();
        while (result.next()) changes.add(new Change(result.getLong(1), result.getString(2),
          result.getString(3), result.getString(4)));
        return changes;
      }
    }
  }

  private List<Map<String,Object>> rows(Connection connection, ShadowTable table, String key)
      throws SQLException {
    String sql = "SELECT " + String.join(",", table.columns()) + " FROM " + table.name()
      + (key == null ? " ORDER BY " + table.keyColumn() : " WHERE " + table.keyColumn() + "=?");
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      if (key != null) statement.setString(1, key);
      try (ResultSet result = statement.executeQuery()) {
        List<Map<String,Object>> rows = new ArrayList<>();
        ResultSetMetaData metadata = result.getMetaData();
        while (result.next()) {
          Map<String,Object> row = new LinkedHashMap<>();
          for (int column = 1; column <= metadata.getColumnCount(); column++)
            row.put(metadata.getColumnLabel(column), result.getObject(column));
          rows.add(row);
        }
        return rows;
      }
    }
  }

  private Map<String,Object> one(Connection connection, ShadowTable table, String key) throws SQLException {
    List<Map<String,Object>> found = rows(connection, table, key);
    return found.isEmpty() ? null : found.getFirst();
  }

  private void upsert(Connection connection, ShadowTable table, Map<String,Object> row) throws SQLException {
    String placeholders = String.join(",", java.util.Collections.nCopies(table.columns().size(), "?"));
    String updates = table.columns().stream().filter(column -> !column.equals(table.keyColumn()))
      .map(column -> column + "=EXCLUDED." + column).collect(java.util.stream.Collectors.joining(","));
    String sql = "INSERT INTO " + table.name() + "(" + String.join(",", table.columns()) + ") VALUES ("
      + placeholders + ") ON CONFLICT (" + table.keyColumn() + ") DO UPDATE SET " + updates;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int index = 0; index < table.columns().size(); index++)
        statement.setObject(index + 1, row.get(table.columns().get(index)));
      statement.executeUpdate();
    }
  }

  private void delete(Connection connection, ShadowTable table, String key) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(
      "DELETE FROM " + table.name() + " WHERE " + table.keyColumn() + "=?")) {
      statement.setString(1, key);
      statement.executeUpdate();
    }
  }

  private void quarantine(Connection connection, Change change, Map<String,Object> row, String reason)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(
      "INSERT INTO order_shadow_orphan_quarantine(change_sequence,table_name,row_key,operation,payload,reason) " +
        "VALUES (?,?,?,?,?,?) ON CONFLICT (change_sequence) DO UPDATE SET " +
        "reason=EXCLUDED.reason,attempts=order_shadow_orphan_quarantine.attempts+1")) {
      statement.setLong(1, change.sequence());
      statement.setString(2, change.tableName());
      statement.setString(3, change.rowKey());
      statement.setString(4, change.operation());
      statement.setString(5, row == null ? null : mapper.writeValueAsString(row));
      statement.setString(6, reason.substring(0, Math.min(reason.length(), 500)));
      statement.executeUpdate();
    }
  }

  private void replayQuarantine() throws SQLException {
    try (Connection source = sourceFactory.open(); Connection targetConnection = target.getConnection();
         PreparedStatement open = targetConnection.prepareStatement(
           "SELECT id,change_sequence,table_name,row_key,operation FROM order_shadow_orphan_quarantine " +
             "WHERE status='OPEN' ORDER BY id LIMIT ?")) {
      open.setInt(1, properties.shadow().batchSize());
      try (ResultSet result = open.executeQuery()) {
        while (result.next()) {
          long id = result.getLong(1);
          ShadowTable table = ShadowTable.named(result.getString(3));
          Map<String,Object> row = one(source, table, result.getString(4));
          try {
            if (row == null) delete(targetConnection, table, result.getString(4));
            else upsert(targetConnection, table, row);
            try (PreparedStatement resolved = targetConnection.prepareStatement(
              "UPDATE order_shadow_orphan_quarantine SET status='RESOLVED',attempts=attempts+1," +
                "resolved_at=CURRENT_TIMESTAMP(6) WHERE id=?")) {
              resolved.setLong(1, id);
              resolved.executeUpdate();
            }
          } catch (SQLException exception) {
            try (PreparedStatement attempted = targetConnection.prepareStatement(
              "UPDATE order_shadow_orphan_quarantine SET attempts=attempts+1,reason=? WHERE id=?")) {
              attempted.setString(1, exception.getMessage().substring(0,
                Math.min(exception.getMessage().length(), 500)));
              attempted.setLong(2, id);
              attempted.executeUpdate();
            }
          }
        }
      }
    }
  }

  private void updateWatermark(Connection connection, long sequence, boolean caughtUp) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(
      "UPDATE order_shadow_watermark SET source_sequence=?,caught_up_at=" +
        (caughtUp ? "CURRENT_TIMESTAMP(6)" : "caught_up_at") + " WHERE replica_name=?")) {
      statement.setLong(1, sequence);
      statement.setString(2, REPLICA);
      statement.executeUpdate();
    }
  }

  private String fingerprint(ShadowTable table, List<Map<String,Object>> rows) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (Map<String,Object> row : rows) for (String column : table.columns()) {
        Object value = row.get(column);
        digest.update((column + "=" + (value == null ? "<null>" : value) + "\n")
          .getBytes(StandardCharsets.UTF_8));
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (Exception exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private long scalar(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
      result.next();
      return result.getLong(1);
    }
  }

  private void execute(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement()) { statement.executeUpdate(sql); }
  }

  private Instant instant(ResultSet result, int column) throws SQLException {
    var timestamp = result.getTimestamp(column);
    return timestamp == null ? null : timestamp.toInstant();
  }

  private record Change(long sequence, String tableName, String rowKey, String operation) {
  }

  public record ReplicationStatus(long generation, long sourceSequence, Instant snapshotCompletedAt,
                                  Instant caughtUpAt) {
  }

  public record TableAudit(String tableName, long sourceCount, long targetCount, String status) {
  }
}
