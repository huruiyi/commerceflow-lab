package vip.fairy.commerceflow.inventory.infrastructure.persistence;

import java.util.List;

import org.apache.ibatis.annotations.Param;
import vip.fairy.commerceflow.inventory.domain.InventoryMovementView;
import vip.fairy.commerceflow.inventory.domain.MerchantInventoryItem;

public interface MerchantInventoryMapper {

  List<MerchantInventoryItem> list(Long merchantId);

  MerchantInventoryItem find(@Param("merchantId") Long merchantId,
                             @Param("productId") Long productId);

  int adjust(@Param("merchantId") Long merchantId, @Param("productId") Long productId,
             @Param("delta") int delta);

  List<InventoryMovementView> movements(@Param("merchantId") Long merchantId,
                                        @Param("productId") Long productId);
}
