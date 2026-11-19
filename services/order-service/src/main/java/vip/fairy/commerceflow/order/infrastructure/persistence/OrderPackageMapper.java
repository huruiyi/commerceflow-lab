package vip.fairy.commerceflow.order.infrastructure.persistence;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import vip.fairy.commerceflow.order.application.OrderPackageService.Command;
import vip.fairy.commerceflow.order.application.OrderPackageService.Line;
import vip.fairy.commerceflow.order.application.OrderPackageService.PostedLine;

@Mapper
public interface OrderPackageMapper {

  Allocation find(String packageId);
  List<PostedLine> lines(String packageId);
  List<PostedLine> actualLines(String packageId);
  List<Line> countedLines(String packageId);
  void setActual(@Param("packageId") String packageId, @Param("line") Line line);
  int markReceived(@Param("packageId") String packageId, @Param("voided") boolean voided);
  int allocatedAtWarehouse(@Param("orderLineId") long orderLineId, @Param("warehouseId") long warehouseId);
  boolean trackingUsed(@Param("orderId") long orderId, @Param("carrier") String carrier,
                       @Param("trackingNumber") String trackingNumber);
  void insert(Command value);
  void insertLine(@Param("packageId") String packageId, @Param("line") Line line);
  int hold(@Param("orderId") Long orderId, @Param("packageId") String packageId);
  int markPosted(String packageId);
  int clearHold(@Param("orderId") Long orderId, @Param("packageId") String packageId);
  void finishIfFullyAllocated(Long orderId);

  /** 订单侧持有的发货数量准入凭证。 */
  record Allocation(long orderId, long merchantId, String carrier, String trackingNumber, boolean posted,
                    long warehouseId, boolean received, boolean voided) {
  }
}
