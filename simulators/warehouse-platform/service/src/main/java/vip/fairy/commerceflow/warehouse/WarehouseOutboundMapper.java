package vip.fairy.commerceflow.warehouse;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
interface WarehouseOutboundMapper {

  void insert(@Param("value") WarehouseOutbounds.Command value, @Param("planned") String planned);
  Row get(String outboundId);
  Row lock(String outboundId);
  long count(@Param("query") WarehouseJobSearch query);
  List<Row> search(@Param("query") WarehouseJobSearch query, @Param("offset") long offset);
  int decide(@Param("id") String id, @Param("status") String status, @Param("actual") String actual);

  record Row(String outboundId, long orderId, long merchantId, long warehouseId, boolean wholeOrder,
             String planned, String actual, String status, Instant createdAt, Instant decidedAt) {
  }
}
