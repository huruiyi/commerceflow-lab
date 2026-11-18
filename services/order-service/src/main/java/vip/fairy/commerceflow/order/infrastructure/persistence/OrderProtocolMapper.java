package vip.fairy.commerceflow.order.infrastructure.persistence;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OrderProtocolMapper {

  int insertCommand(@Param("messageId") String messageId, @Param("fingerprint") String fingerprint);

  List<Map<String,Object>> lockWaitingProcess(@Param("orderId") Long orderId);

  int insertResult(
    @Param("resultId") String resultId,
    @Param("processId") String processId,
    @Param("orderId") Long orderId,
    @Param("resultType") String resultType,
    @Param("outcome") String outcome,
    @Param("fingerprint") String fingerprint,
    @Param("payload") String payload,
    @Param("status") String status,
    @Param("error") String error,
    @Param("correlationId") String correlationId,
    @Param("causationId") String causationId,
    @Param("traceparent") String traceparent);

  int requireCompensation(@Param("error") String error, @Param("processId") String processId);

  List<Map<String,Object>> recoverable();

  Integer countProcess(@Param("processId") String processId);

  int resetOutbox(@Param("processId") String processId);

  int retryProcess(
    @Param("operator") String operator,
    @Param("reason") String reason,
    @Param("processId") String processId);

  int markManualReview(
    @Param("operator") String operator,
    @Param("reason") String reason,
    @Param("processId") String processId);

  int markUnknownResults();

  int insertCancellation(
    @Param("processId") String processId,
    @Param("orderId") Long orderId,
    @Param("commandId") String commandId,
    @Param("fingerprint") String fingerprint,
    @Param("correlationId") String correlationId,
    @Param("causationId") String causationId,
    @Param("traceparent") String traceparent);

  int rejectResult(@Param("resultId") String resultId);

  int rejectConfirmedResult(@Param("resultId") String resultId);

  int completeProcess(@Param("processId") String processId);

  int acknowledgeOutbox(@Param("processId") String processId);

  int advanceProcess(
    @Param("step") String step,
    @Param("waiting") String waiting,
    @Param("processId") String processId);

  int insertOutbox(
    @Param("id") String id,
    @Param("processId") String processId,
    @Param("orderId") Long orderId,
    @Param("target") String target,
    @Param("type") String type,
    @Param("fingerprint") String fingerprint,
    @Param("payload") String payload,
    @Param("correlationId") String correlationId,
    @Param("causationId") String causationId,
    @Param("traceparent") String traceparent);

  int insertEvent(
    @Param("orderId") Long orderId,
    @Param("actorId") Long actorId,
    @Param("type") String type,
    @Param("from") String from,
    @Param("to") String to,
    @Param("detail") String detail);

  int transition(
    @Param("to") String to,
    @Param("reason") String reason,
    @Param("carrier") String carrier,
    @Param("tracking") String tracking,
    @Param("orderId") Long orderId,
    @Param("from") String from);

  List<String> lockStatus(@Param("orderId") Long orderId);

  List<Map<String,Object>> findCommandReceipt(@Param("messageId") String messageId);

  List<Map<String,Object>> findResultReceipt(@Param("resultId") String resultId);

  int finishCommand(
    @Param("status") String status,
    @Param("payload") String payload,
    @Param("error") String error,
    @Param("messageId") String messageId);
}
