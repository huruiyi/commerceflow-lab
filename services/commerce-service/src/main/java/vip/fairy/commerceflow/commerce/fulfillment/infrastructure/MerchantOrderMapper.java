package vip.fairy.commerceflow.commerce.fulfillment.infrastructure;

import vip.fairy.commerceflow.commerce.ordering.domain.ShopOrderView;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

public interface MerchantOrderMapper {

  List<ShopOrderView> list(Long merchantId);

  Optional<ShopOrderView> lock(@Param("merchantId") Long merchantId, @Param("orderId") Long orderId);

  int confirm(Long orderId);

  int ship(@Param("orderId") Long orderId, @Param("carrier") String carrier, @Param("tracking") String tracking);

  int updateShipment(@Param("orderId") Long orderId,
                     @Param("carrier") String carrier,
                     @Param("tracking") String tracking);
}
