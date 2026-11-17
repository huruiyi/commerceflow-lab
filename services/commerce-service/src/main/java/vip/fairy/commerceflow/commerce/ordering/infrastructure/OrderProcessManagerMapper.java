package vip.fairy.commerceflow.commerce.ordering.infrastructure;

import vip.fairy.commerceflow.commerce.ordering.domain.OrderProcessView;
import vip.fairy.commerceflow.commerce.ordering.domain.OrderResultInboxView;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.Optional;

public interface OrderProcessManagerMapper {

  Optional<OrderProcessView> lockProcess(String processId);

  Optional<OrderProcessView> lockActive(@Param("orderId") Long orderId,
                                        @Param("waitingFor") String waitingFor);

  void insertProcess(@Param("processId") String processId, @Param("orderId") Long orderId,
                     @Param("processType") String processType,
                     @Param("waitingFor") String waitingFor,
                     @Param("commandId") String commandId,
                     @Param("fingerprint") String fingerprint,
                     @Param("correlationId") String correlationId,
                     @Param("causationId") String causationId,
                     @Param("traceparent") String traceparent,
                     @Param("deadline") Instant deadline);

  void insertOutbox(@Param("commandId") String commandId,
                    @Param("processId") String processId, @Param("orderId") Long orderId,
                    @Param("target") String target, @Param("commandType") String commandType,
                    @Param("payload") String payload, @Param("fingerprint") String fingerprint,
                    @Param("deadline") Instant deadline,
                    @Param("correlationId") String correlationId,
                    @Param("causationId") String causationId,
                    @Param("traceparent") String traceparent);

  Optional<OrderResultInboxView> result(String resultId);

  void insertResult(@Param("resultId") String resultId, @Param("orderId") Long orderId,
                    @Param("resultType") String resultType, @Param("outcome") String outcome,
                    @Param("payload") String payload, @Param("fingerprint") String fingerprint,
                    @Param("correlationId") String correlationId,
                    @Param("causationId") String causationId,
                    @Param("traceparent") String traceparent);

  int attachResult(@Param("resultId") String resultId, @Param("processId") String processId,
                   @Param("status") String status, @Param("detail") String detail);

  int finishProcess(@Param("processId") String processId, @Param("status") String status,
                    @Param("error") String error);

  int acknowledgeCommand(String commandId);

  int markExpired(Instant now);

  int recover(@Param("processId") String processId, @Param("operator") String operator,
              @Param("reason") String reason, @Param("nextCheckAt") Instant nextCheckAt);
}
