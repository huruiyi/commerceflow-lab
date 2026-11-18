package vip.fairy.commerceflow.simulator.payment.mapper;

import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import vip.fairy.commerceflow.simulator.payment.domain.PaymentAuditLog;

public interface PaymentAuditLogMapper extends BaseMapper<PaymentAuditLog> {

  List<PaymentAuditLog> listRecent();

  String lockChainHead();


  void updateChainHead(String hash);
}
