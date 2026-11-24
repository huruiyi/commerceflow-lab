package vip.fairy.commerceflow.catalog.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import vip.fairy.commerceflow.catalog.api.CatalogProductV1;
import vip.fairy.commerceflow.catalog.application.CatalogSkuSearch.Facet;
import vip.fairy.commerceflow.catalog.application.CatalogSearchService.Criteria;

public interface CatalogSearchMapper {

  State lockState();
  State state();
  void requestRebuild();
  void switchGeneration(long generation);
  void dropExpired(long generation);
  long eventBoundary();
  List<Change> pending(long throughId);
  long pendingCount();
  void acknowledge(@Param("ids") List<Long> ids);
  void deleteDocuments(@Param("generation") long generation, @Param("products") List<Long> products);
  void copyDocuments(@Param("generation") long generation, @Param("products") List<Long> products);
  long count(Criteria criteria);
  List<Facet> skuFacets(Criteria criteria);
  List<CatalogProductV1> search(Criteria criteria);

  /** 当前代次和已受理的重建请求。 */
  record State(long generation, boolean rebuildRequested, Instant rebuiltAt) {
  }

  /** 搜索消费者读取的目录事件标识。 */
  record Change(long id, long productId) {
  }
}
