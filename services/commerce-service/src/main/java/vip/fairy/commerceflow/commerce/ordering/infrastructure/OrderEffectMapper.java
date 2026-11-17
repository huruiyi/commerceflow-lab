package vip.fairy.commerceflow.commerce.ordering.infrastructure;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import vip.fairy.commerceflow.commerce.ordering.domain.OrderEffectOutboxEvent;

public interface OrderEffectMapper {

  void insert(@Param("eventId") String eventId,
              @Param("aggregateId") String aggregateId,
              @Param("eventType") String eventType,
              @Param("topic") String topic,
              @Param("payload") String payload);

  List<OrderEffectOutboxEvent> pending();

  int published(Long id);

  void retry(Long id);

  int claim(@Param("group") String group,
            @Param("eventId") String eventId,
            @Param("topic") String topic);
}
