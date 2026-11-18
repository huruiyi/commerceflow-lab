package vip.fairy.commerceflow.payment.infrastructure.persistence;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PaymentEffectMapper {

  List<Long> readyEffectIds(@Param("batchSize") int batchSize);

  List<String> readyRefundCommandIds(@Param("batchSize") int batchSize);

  int claimEffect(@Param("id") long id);

  Map<String,Object> findEffect(@Param("id") long id);

  int markEffectPublished(@Param("id") long id);

  int retryEffect(@Param("error") String error, @Param("id") long id);

  Map<String,Object> findRefundChannelData(@Param("refundNumber") String refundNumber);

  int insertRefundProcess(
    @Param("processId") String processId,
    @Param("orderId") long orderId,
    @Param("commandId") String commandId,
    @Param("fingerprint") String fingerprint,
    @Param("correlationId") String correlationId,
    @Param("causationId") String causationId,
    @Param("traceparent") String traceparent);

  int insertRefundCommand(
    @Param("commandId") String commandId,
    @Param("processId") String processId,
    @Param("orderId") long orderId,
    @Param("fingerprint") String fingerprint,
    @Param("payload") String payload,
    @Param("correlationId") String correlationId,
    @Param("causationId") String causationId,
    @Param("traceparent") String traceparent);

  int markRefundProcessing(@Param("refundNumber") String refundNumber);

  int claimRefundCommand(@Param("commandId") String commandId);

  String refundCommandPayload(@Param("commandId") String commandId);

  int assignChannelRefundNo(
    @Param("channelRefundNo") String channelRefundNo,
    @Param("refundNumber") String refundNumber);

  int acknowledgeRefundCommand(@Param("commandId") String commandId);

  int retryRefundCommand(@Param("error") String error, @Param("commandId") String commandId);

  int markRefundInventoryRestored(@Param("refundNumber") String refundNumber);

  Map<String,Object> findRefundPreviousState(@Param("refundNumber") String refundNumber);
}
