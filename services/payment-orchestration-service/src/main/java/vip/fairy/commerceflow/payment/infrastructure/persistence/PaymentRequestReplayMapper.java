package vip.fairy.commerceflow.payment.infrastructure.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PaymentRequestReplayMapper {

  int deleteExpired();

  int insertRequest(
    @Param("requestId") String requestId,
    @Param("service") String service,
    @Param("contentHash") String contentHash);
}
