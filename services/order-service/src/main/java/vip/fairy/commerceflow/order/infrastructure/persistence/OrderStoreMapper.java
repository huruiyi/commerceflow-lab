package vip.fairy.commerceflow.order.infrastructure.persistence;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import vip.fairy.commerceflow.order.application.contract.v1.OrderStoreV1.AddressSnapshot;
import vip.fairy.commerceflow.order.application.contract.v1.OrderStoreV1.AddressView;
import vip.fairy.commerceflow.order.application.contract.v1.OrderStoreV1.CreateOrder;
import vip.fairy.commerceflow.order.application.contract.v1.OrderStoreV1.EventView;
import vip.fairy.commerceflow.order.application.contract.v1.OrderStoreV1.ItemSnapshot;
import vip.fairy.commerceflow.order.application.contract.v1.OrderStoreV1.ItemView;
import vip.fairy.commerceflow.order.application.contract.v1.OrderStoreV1.Mutation;
import vip.fairy.commerceflow.order.application.contract.v1.OrderStoreV1.OrderView;
import vip.fairy.commerceflow.order.application.contract.v1.OrderStoreV1.Query;
import vip.fairy.commerceflow.order.application.contract.v1.OrderStoreV1.ReviewableItem;

@Mapper
public interface OrderStoreMapper {

  OrderView findByNumber(@Param("orderNumber") String orderNumber);

  OrderView findById(@Param("orderId") Long orderId);

  List<OrderView> listCustomer(@Param("customerId") Long customerId);

  OrderView findCustomer(Query query);

  List<OrderView> listMerchant(@Param("merchantId") Long merchantId);

  OrderView findMerchant(Query query);

  List<ItemView> items(@Param("orderId") Long orderId);

  AddressView address(@Param("orderId") Long orderId);

  List<EventView> events(@Param("orderId") Long orderId);

  String status(@Param("orderId") Long orderId);

  Long customerId(@Param("orderId") Long orderId);

  ReviewableItem reviewable(Query query);

  InboxEntry lockMutation(@Param("mutationId") String mutationId);

  int insertOrder(@Param("request") CreateOrder request,
    @Param("key") GeneratedOrderKey key);

  int insertItem(@Param("orderId") Long orderId,
    @Param("item") ItemSnapshot item);

  int insertAddress(@Param("orderId") Long orderId,
    @Param("address") AddressSnapshot address);

  int insertMutation(@Param("mutationId") String mutationId,
    @Param("fingerprint") String fingerprint);

  int finishMutation(@Param("mutationId") String mutationId,
    @Param("response") String response);

  int insertEvent(OrderEvent event);

  int markLogisticsDelivered(Mutation mutation);

  int updateStatus(Mutation mutation);

  int updateInventoryStatus(Mutation mutation);

  int startInventoryCommit(Mutation mutation);

  int startCancellation(Mutation mutation);

  int finishCancellation(Mutation mutation);

  int restorePaidCancellationRace(Mutation mutation);

  int restorePaidLateSuccess(Mutation mutation);

  int markReceived(Mutation mutation);

  int markCompleted(Mutation mutation);

  int confirmByMerchant(Mutation mutation);

  int shipByMerchant(Mutation mutation);

  int updateShipment(Mutation mutation);

  record InboxEntry(String payloadFingerprint, String responsePayload) {
  }

  record OrderEvent(Long orderId, Long actorId, String type, String fromStatus, String toStatus,
                    String detail, String trigger, Long scheduledTaskId) {
  }

  class GeneratedOrderKey {

    /** MySQL 创建订单时生成的内部主键。 */
    private Long id;

    public Long getId() {
      return id;
    }

    public void setId(Long id) {
      this.id = id;
    }
  }
}
