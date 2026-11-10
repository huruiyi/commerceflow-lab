package com.tanerinal.payment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tanerinal.payment.domain.PaymentAdminMfaCredential;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface PaymentAdminMfaCredentialMapper extends BaseMapper<PaymentAdminMfaCredential> {
  @Delete("DELETE FROM payment_admin_mfa_recovery_code WHERE user_id=#{userId}")
  void deleteRecoveryCodes(long userId);

  @Insert("INSERT INTO payment_admin_mfa_recovery_code(user_id, code_hash) VALUES(#{userId}, #{hash})")
  void insertRecoveryCode(@Param("userId") long userId, @Param("hash") String hash);

  @Select("SELECT id, code_hash FROM payment_admin_mfa_recovery_code WHERE user_id=#{userId} AND used_at IS NULL")
  List<RecoveryCodeRow> selectUnusedRecoveryCodes(long userId);

  @Update("UPDATE payment_admin_mfa_recovery_code SET used_at=CURRENT_TIMESTAMP(6) WHERE id=#{id} AND used_at IS NULL")
  int consumeRecoveryCode(long id);

  record RecoveryCodeRow(long id, String codeHash) { }
}
