package vip.fairy.commerceflow.warehouse;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
interface WarehouseReturnMapper {

  void insert(WarehouseReturns.Command value);
  WarehouseReturns.Receipt lock(String id);
  long count(@Param("query") WarehouseJobSearch query);
  List<WarehouseReturns.Receipt> search(@Param("query") WarehouseJobSearch query, @Param("offset") long offset);
  List<WarehouseReturns.Receipt> evidence(@Param("merchantId") long merchantId, @Param("orderIds") List<Long> orderIds);
  int receive(@Param("id") String id, @Param("quantity") int quantity, @Param("status") String status);
  int inspect(@Param("id") String id, @Param("status") String status);
  int supplement(@Param("id") String id, @Param("evidenceId") String evidenceId,
                 @Param("quantity") int quantity, @Param("note") String note);
}
