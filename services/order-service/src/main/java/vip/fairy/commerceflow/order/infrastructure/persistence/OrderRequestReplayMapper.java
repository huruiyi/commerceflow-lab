package vip.fairy.commerceflow.order.infrastructure.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OrderRequestReplayMapper {

  int deleteExpired();

  int insert(@Param("requestId") String requestId, @Param("service") String service,
             @Param("fingerprint") String fingerprint);
}
