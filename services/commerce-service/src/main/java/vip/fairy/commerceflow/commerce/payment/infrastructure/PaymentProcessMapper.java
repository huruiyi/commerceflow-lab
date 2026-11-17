package vip.fairy.commerceflow.commerce.payment.infrastructure;

import java.time.Instant;

import org.apache.ibatis.annotations.Param;

import vip.fairy.commerceflow.commerce.payment.domain.PaymentProcessView;

public interface PaymentProcessMapper {

  PaymentProcessView findByCommandId(String commandId);

  PaymentProcessView findByCorrelation(@Param("orderId") Long orderId,
                                       @Param("correlationId") String correlationId);

  String resultFingerprint(String resultId);

  void insertProcess(@Param("processId") String processId, @Param("orderId") Long orderId,
                     @Param("commandId") String commandId, @Param("fingerprint") String fingerprint,
                     @Param("correlationId") String correlationId,
                     @Param("causationId") String causationId,
                     @Param("traceparent") String traceparent,
                     @Param("deadline") Instant deadline);

  void insertCommand(@Param("commandId") String commandId, @Param("processId") String processId,
                     @Param("orderId") Long orderId, @Param("fingerprint") String fingerprint,
                     @Param("payload") String payload, @Param("correlationId") String correlationId,
                     @Param("causationId") String causationId,
                     @Param("traceparent") String traceparent,
                     @Param("deadline") Instant deadline);

  void insertResult(@Param("resultId") String resultId, @Param("processId") String processId,
                    @Param("orderId") Long orderId, @Param("resultType") String resultType,
                    @Param("outcome") String outcome, @Param("fingerprint") String fingerprint,
                    @Param("payload") String payload, @Param("processingStatus") String processingStatus,
                    @Param("errorDetail") String errorDetail,
                    @Param("correlationId") String correlationId,
                    @Param("causationId") String causationId,
                    @Param("traceparent") String traceparent);

  int applyResult(@Param("processId") String processId, @Param("status") String status,
                  @Param("error") String error);

  int markExpired(@Param("now") Instant now);

  int retry(@Param("processId") String processId, @Param("operator") String operator,
            @Param("reason") String reason, @Param("deadline") Instant deadline);

  int requeueCommand(@Param("processId") String processId, @Param("deadline") Instant deadline);

  PaymentProcessView findByProcessId(String processId);
}
