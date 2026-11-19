package vip.fairy.commerceflow.order.infrastructure.persistence;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import vip.fairy.commerceflow.order.application.OrderLineRefundService.Command;
import vip.fairy.commerceflow.order.application.contract.v1.OrderStoreV1.ItemView;

@Mapper
public interface OrderLineRefundMapper {

  OrderLock lockOrder(Long orderId);

  Admission find(String caseId);

  ItemView item(Long orderId, Long orderLineId);

  int settledAtWarehouse(@Param("orderId") long orderId, @Param("orderLineId") long orderLineId,
                         @Param("warehouseId") long warehouseId);

  int heldCount(Long orderId);

  void hold(Command value);

  int finish(String caseId, String status);

  void closeEmptyOrder(Long orderId);

  List<ItemView> shipmentItems(Long orderId);

  record OrderLock(Long customerId, String status, String inventoryStatus, String shipmentIntentKey) {
  }

  record Admission(Long orderId, Long orderLineId, int quantity, String status, long warehouseId) {
  }
}
