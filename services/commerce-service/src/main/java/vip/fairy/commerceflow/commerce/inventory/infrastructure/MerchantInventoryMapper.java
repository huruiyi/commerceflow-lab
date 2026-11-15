package vip.fairy.commerceflow.commerce.inventory.infrastructure;

import java.util.List;

import vip.fairy.commerceflow.commerce.inventory.domain.InventoryMovementView;
import vip.fairy.commerceflow.commerce.inventory.domain.MerchantInventoryItem;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;

public interface MerchantInventoryMapper {

  List<MerchantInventoryItem> list(Long merchantId);


  MerchantInventoryItem find(@Param("merchantId") Long merchantId,
                             @Param("productId") Long productId);


  int adjust(@Param("merchantId") Long merchantId, @Param("productId") Long productId,
             @Param("delta") int delta);

  int updateCatalogSnapshot(@Param("productId") Long productId,
                            @Param("merchantId") Long merchantId,
                            @Param("productName") String productName,
                            @Param("thumbnailUrl") String thumbnailUrl,
                            @Param("unitPrice") BigDecimal unitPrice);


  List<InventoryMovementView> movements(@Param("merchantId") Long merchantId,
                                        @Param("productId") Long productId);
}
