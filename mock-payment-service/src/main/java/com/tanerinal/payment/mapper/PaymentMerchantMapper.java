package com.tanerinal.payment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tanerinal.payment.domain.PaymentMerchant;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

public interface PaymentMerchantMapper extends BaseMapper<PaymentMerchant> {
  @Insert("INSERT INTO merchant_callback_endpoint(merchant_id, callback_url) VALUES(#{merchantId}, #{url})")
  void insertCallbackEndpoint(@Param("merchantId") long merchantId, @Param("url") String url);
}
