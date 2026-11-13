package vip.fairy.commerceflow.commerce.payment.infrastructure;

import vip.fairy.commerceflow.commerce.payment.domain.OutboxEvent;

import java.util.List;

import org.apache.ibatis.annotations.Param;

public interface OutboxMapper {

  void insert(
    @Param("eventId") String eventId,
    @Param("aggregateType") String aggregateType,
    @Param("aggregateId") String aggregateId,
    @Param("eventType") String eventType,
    @Param("topic") String topic,
    @Param("payload") String payload
  );

  List<OutboxEvent> pending();
  int published(Long id);
  void retry(Long id);
  int consume(@Param("group") String group, @Param("eventId") String eventId, @Param("topic") String topic);
}
