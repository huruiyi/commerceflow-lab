package lab.commerceflow.payment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import lab.commerceflow.payment.domain.PaymentAuditLog;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface PaymentAuditLogMapper extends BaseMapper<PaymentAuditLog> {
  @Select("SELECT last_hash FROM payment_audit_chain_head WHERE id=1 FOR UPDATE")
  String lockChainHead();

  @Update("UPDATE payment_audit_chain_head SET last_hash=#{hash}, updated_at=CURRENT_TIMESTAMP(6) WHERE id=1")
  void updateChainHead(String hash);
}
