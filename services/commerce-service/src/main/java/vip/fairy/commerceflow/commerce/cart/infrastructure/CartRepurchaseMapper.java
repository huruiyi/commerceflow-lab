package vip.fairy.commerceflow.commerce.cart.infrastructure;

import org.apache.ibatis.annotations.Param;

public interface CartRepurchaseMapper {

  Receipt find(@Param("customerId") Long customerId, @Param("requestId") String requestId);

  void insert(@Param("customerId") Long customerId, @Param("requestId") String requestId,
              @Param("orderId") Long orderId, @Param("resultJson") String resultJson);

  record Receipt(Long orderId, String resultJson) {
  }
}
