package vip.fairy.commerceflow.warehouse;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
interface WarehouseReturnMapper {

  void insert(WarehouseReturns.Command value);
  WarehouseReturns.Receipt lock(String id);
  List<WarehouseReturns.Receipt> list(Long orderId);
  int receive(@Param("id") String id, @Param("quantity") int quantity, @Param("status") String status);
  int inspect(@Param("id") String id, @Param("status") String status);
  int supplement(@Param("id") String id, @Param("evidenceId") String evidenceId,
                 @Param("quantity") int quantity, @Param("note") String note);
}
