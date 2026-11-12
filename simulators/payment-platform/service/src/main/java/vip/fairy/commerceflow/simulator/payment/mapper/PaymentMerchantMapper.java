package vip.fairy.commerceflow.simulator.payment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import vip.fairy.commerceflow.simulator.payment.domain.PaymentMerchant;
import org.apache.ibatis.annotations.Param;

public interface PaymentMerchantMapper extends BaseMapper<PaymentMerchant> {

  void insertCallbackEndpoint(@Param("merchantId") long merchantId, @Param("url") String url);
}
