package vip.fairy.commerceflow.simulator.payment.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import vip.fairy.commerceflow.simulator.payment.domain.ChannelPayout;

public interface ChannelPayoutMapper {

  ChannelPayout find(String attemptId);

  ChannelPayout lock(String attemptId);

  void create(ChannelPayout attempt);

  List<ChannelPayout> pending();

  int resolve(String attemptId);

  List<ChannelPayout> callbacks();

  void notified(@Param("attemptId") String attemptId, @Param("delivered") boolean delivered);

  List<ChannelPayout> recent();
}
