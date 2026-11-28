package vip.fairy.commerceflow.fulfillment.application;

import java.time.Instant;
import java.util.UUID;

import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import vip.fairy.commerceflow.fulfillment.domain.ExecutionTask;
import vip.fairy.commerceflow.fulfillment.domain.FulfillmentCommand.Operation;
import vip.fairy.commerceflow.fulfillment.domain.RecoveryRecord;
import vip.fairy.commerceflow.fulfillment.infrastructure.ExecutionMapper;
import vip.fairy.commerceflow.fulfillment.infrastructure.FulfillmentMode;
import vip.fairy.commerceflow.fulfillment.infrastructure.ShadowDatabases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FulfillmentReviewTest {

  private final UUID commandId = UUID.randomUUID();
  private final UUID recoveryId = UUID.randomUUID();
  private final ExecutionTask task = new ExecutionTask(commandId.toString(), 42, 7, Operation.SHIP,
    "ORD-42", "Carrier", "TRACK-42", null, null, "WAREHOUSE", "REVIEW_REQUIRED", "WAREHOUSE_ATTEMPT_FAILED",
    null, null);
  private final RecoveryRecord record = new RecoveryRecord(task.commandId(), recoveryId.toString(), 5, task.step(),
    task.errorCode(), "Warehouse confirmed", "Original outbound", Instant.EPOCH);
  private final SqlSession session = mock(SqlSession.class);
  private final ExecutionMapper store = mock(ExecutionMapper.class);
  private final FulfillmentDownstream downstream = mock(FulfillmentDownstream.class);
  private FulfillmentReview reviews;

  @BeforeEach
  void setUp() {
    var databases = mock(ShadowDatabases.class);
    var mode = mock(FulfillmentMode.class);
    when(databases.target()).thenReturn(session);
    when(session.getMapper(ExecutionMapper.class)).thenReturn(store);
    when(store.find(task.commandId())).thenReturn(task);
    when(store.lockTask(task)).thenReturn(task.commandId());
    when(mode.execution()).thenReturn(true);
    reviews = new FulfillmentReview(databases, mode, downstream);
  }

  @Test
  void missingOrMismatchedDispatchLeavesTheTaskAndAuditUnchanged() {
    assertThatExceptionOfType(ResponseStatusException.class)
      .isThrownBy(this::retry)
      .satisfies(error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

    verify(store, never()).requeue(any());
    verify(store, never()).insertRecovery(any(), any());
    verify(session, never()).commit();
  }

  @Test
  void recoveredEvidenceRequeuesOnlyOnceAndReplayReturnsTheSavedAudit() {
    when(downstream.hasMatchingWarehouseDispatch(task)).thenReturn(true);
    when(store.requeue(task)).thenReturn(1);
    when(store.recovery(task, recoveryId.toString())).thenReturn(null, record);
    assertThat(retry()).isEqualTo(record);
    assertThat(retry()).isEqualTo(record);

    verify(downstream).hasMatchingWarehouseDispatch(task);
    verify(store).requeue(task);
    verify(store).insertRecovery(eq(task), any());
    verify(session).commit();
  }

  @Test
  void existingRecoveryStillRejectsChangesToTheOriginalRequest() {
    when(store.recovery(task, recoveryId.toString())).thenReturn(record);
    assertThatExceptionOfType(ResponseStatusException.class)
      .isThrownBy(() -> reviews.retry(commandId, recoveryId, 5, record.reason(), "Other outbound", 42L, false))
      .satisfies(error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

    verifyNoInteractions(downstream);
    verify(store, never()).requeue(any());
  }

  private RecoveryRecord retry() {
    return reviews.retry(commandId, recoveryId, 5, record.reason(), record.evidence(), 42L, false);
  }
}
