package vip.fairy.commerceflow.catalog.infrastructure.persistence;

import vip.fairy.commerceflow.catalog.application.CatalogProductEventV1;
import vip.fairy.commerceflow.catalog.domain.CatalogEventOutbox;

import java.time.Instant;
import java.util.List;

import org.apache.ibatis.annotations.Param;

public interface CatalogEventOutboxMapper {

  Backlog backlog();

  record Backlog(long pending, long oldestSeconds, long dead) {
  }

  int insert(CatalogProductEventV1 event, String payload);

  List<CatalogEventOutbox> pending(int batchSize);

  int published(Long id);

  int failed(@Param("id") Long id, @Param("maxAttempts") int maxAttempts,
             @Param("nextAttemptAt") Instant nextAttemptAt, @Param("error") String error);
}
