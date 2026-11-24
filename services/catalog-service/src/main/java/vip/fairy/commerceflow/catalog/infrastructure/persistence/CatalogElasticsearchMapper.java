package vip.fairy.commerceflow.catalog.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import vip.fairy.commerceflow.catalog.infrastructure.persistence.CatalogSearchMapper.Change;

public interface CatalogElasticsearchMapper {

  int PAGE_SIZE = 200;

  State state();
  State lockState();
  List<Long> productIdsAfter(long productId);
  List<Change> pending(long throughId);
  boolean hasPending();
  Backlog backlog();
  void acknowledge(@Param("ids") List<Long> ids);
  void switchGeneration(@Param("generation") long generation, @Param("catalogGeneration") long catalogGeneration);

  /** 可供读取的 Elasticsearch 代次及对应的目录重建代次。 */
  record State(long generation, long catalogGeneration) {
  }

  /** 尚未确认写入 Elasticsearch 的事件数量及最早创建时间。 */
  record Backlog(long pending, Instant oldestAt) {
  }
}
