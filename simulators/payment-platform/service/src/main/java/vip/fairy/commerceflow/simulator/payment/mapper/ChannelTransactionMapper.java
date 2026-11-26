package vip.fairy.commerceflow.simulator.payment.mapper;

import org.apache.ibatis.annotations.Param;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import vip.fairy.commerceflow.simulator.payment.domain.ChannelTransaction;

public interface ChannelTransactionMapper extends BaseMapper<ChannelTransaction> {

  int insertForRequest(ChannelTransaction transaction);

  ChannelTransaction lockRefundPayment(@Param("merchantId") Long merchantId,
                                       @Param("transactionNo") String transactionNo);
}
