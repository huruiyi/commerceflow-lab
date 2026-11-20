package vip.fairy.commerceflow.payment.infrastructure.persistence;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PaymentReportOwnershipMapper {

  void remember(@Param("orderId") long orderId, @Param("merchantId") Long merchantId,
                @Param("status") String status);

  List<Long> orderIdsAfter(@Param("afterOrderId") long afterOrderId, @Param("limit") int limit);
}
