package vip.fairy.commerceflow.simulator.payment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import vip.fairy.commerceflow.simulator.payment.domain.PaymentAuditLog;

public interface PaymentAuditLogMapper extends BaseMapper<PaymentAuditLog> {

  String lockChainHead();


  void updateChainHead(String hash);
}
