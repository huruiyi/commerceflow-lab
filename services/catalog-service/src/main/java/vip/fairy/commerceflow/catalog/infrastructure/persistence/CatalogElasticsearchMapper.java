package vip.fairy.commerceflow.catalog.infrastructure.persistence;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import vip.fairy.commerceflow.catalog.infrastructure.persistence.CatalogSearchMapper.Change;

public interface CatalogElasticsearchMapper {

  State state();
  State lockState();
  List<Long> productIdsAfter(long productId);
  List<Change> pending(long throughId);
  boolean hasPending();
  void acknowledge(@Param("ids") List<Long> ids);
  void switchGeneration(@Param("generation") long generation, @Param("catalogGeneration") long catalogGeneration);

  /** 可供读取的 Elasticsearch 代次及对应的目录重建代次。 */
  record State(long generation, long catalogGeneration) {
  }
}
