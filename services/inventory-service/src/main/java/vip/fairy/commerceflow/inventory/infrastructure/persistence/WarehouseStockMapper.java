package vip.fairy.commerceflow.inventory.infrastructure.persistence;

import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface WarehouseStockMapper {

  List<AvailableStock> lockAvailable(@Param("products") List<Long> products);

  void createDefault(Long productId);
  void ensureStock(@Param("productId") long productId, @Param("warehouseId") long warehouseId);

  int adjust(@Param("productId") long productId, @Param("warehouseId") long warehouseId,
             @Param("available") int available, @Param("reserved") int reserved, @Param("sold") int sold);

  /** 仓库选路时读取的库存和优先级。 */
  record AvailableStock(long warehouseId, long productId, int priority, int available) {
  }
}
