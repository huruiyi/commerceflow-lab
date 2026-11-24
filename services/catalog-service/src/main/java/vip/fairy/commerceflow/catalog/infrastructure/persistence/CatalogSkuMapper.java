package vip.fairy.commerceflow.catalog.infrastructure.persistence;

import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface CatalogSkuMapper {

  void backfillDefaults();

  void insertDefault(@Param("product") long product, @Param("merchant") long merchant);

  List<Row> list(@Param("merchant") long merchant, @Param("product") long product);

  Row find(@Param("merchant") long merchant, @Param("product") long product, @Param("sku") long sku);

  Row findRequest(@Param("merchant") long merchant, @Param("requestId") String requestId);

  List<Row> unbound();

  List<Row> forStockIds(@Param("ids") List<Long> ids);

  List<Row> activeVariants(@Param("ids") List<Long> ids);

  int bindStock(@Param("sku") long sku, @Param("stockProductId") long stockProductId);

  void insert(Row sku);

  int update(@Param("sku") long sku, @Param("version") long version, @Param("code") String code,
             @Param("unitPrice") BigDecimal unitPrice, @Param("lifecycleStatus") String lifecycleStatus);

  /** 目录 SKU、版本及现有库存销售单元的绑定。 */
  record Row(Long id, long productId, long merchantId, String code, String specKey, String specifications,
             boolean defaultSku, BigDecimal unitPrice, String lifecycleStatus, long businessVersion,
             String requestId, String createFingerprint, Long stockProductId) {
  }
}
