package vip.fairy.commerceflow.commerce.ordering.infrastructure;

import vip.fairy.commerceflow.commerce.ordering.domain.NewOrder;
import vip.fairy.commerceflow.commerce.ordering.domain.OrderAddressSnapshot;
import vip.fairy.commerceflow.commerce.ordering.domain.OrderAddressView;
import vip.fairy.commerceflow.commerce.ordering.domain.OrderEventView;
import vip.fairy.commerceflow.commerce.ordering.domain.OrderItemSnapshot;
import vip.fairy.commerceflow.commerce.ordering.domain.OrderItemView;
import vip.fairy.commerceflow.commerce.ordering.domain.ShopOrderView;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ShopOrderMapper {

  String status(Long id);

  int markLogisticsDelivered(@Param("id") Long id, @Param("deliveredAt") Instant deliveredAt);

  @Options(useGeneratedKeys = true, keyProperty = "id")
  int insertOrder(NewOrder order);

  void insertItem(@Param("orderId") Long orderId,
                  @Param("item") OrderItemSnapshot item,
                  @Param("subtotal") BigDecimal subtotal);

  void insertAddress(
    @Param("orderId") Long orderId,
    @Param("a") OrderAddressSnapshot address
  );

  default void insertEvent(Long orderId, Long actorId, String type, String fromStatus,
                           String toStatus, String detail) {
    insertEventDetailed(orderId, actorId, type, fromStatus, toStatus, detail, "USER", null);
  }

  void insertEventDetailed(@Param("orderId") Long orderId, @Param("actorId") Long actorId,
                           @Param("type") String type, @Param("fromStatus") String fromStatus,
                           @Param("toStatus") String toStatus, @Param("detail") String detail,
                           @Param("triggerSource") String triggerSource,
                           @Param("scheduledTaskId") Long scheduledTaskId);

  List<ShopOrderView> list(Long customerId);

  Optional<ShopOrderView> find(@Param("customerId") Long customerId, @Param("id") Long id);

  Optional<ShopOrderView> lock(@Param("customerId") Long customerId, @Param("id") Long id);

  Optional<ShopOrderView> lockById(Long id);

  List<OrderItemView> items(Long orderId);

  OrderAddressView address(Long orderId);

  List<OrderEventView> events(Long orderId);

  Long orderCustomerId(Long orderId);

  int updateStatus(@Param("id") Long id,
                   @Param("fromStatus") String fromStatus,
                   @Param("toStatus") String toStatus);

  int updateInventoryStatus(@Param("id") Long id,
                            @Param("fromStatus") String fromStatus,
                            @Param("toStatus") String toStatus);

  int startCancellation(@Param("id") Long id, @Param("reason") String reason);

  int finishCancellation(Long id);

  int restorePaidAfterCancellationRace(Long id);

  int restorePaidAfterLateSuccess(Long id);

  int markReceived(@Param("id") Long id, @Param("receivedAt") Instant receivedAt);

  int markCompleted(@Param("id") Long id, @Param("completedAt") Instant completedAt);
}
