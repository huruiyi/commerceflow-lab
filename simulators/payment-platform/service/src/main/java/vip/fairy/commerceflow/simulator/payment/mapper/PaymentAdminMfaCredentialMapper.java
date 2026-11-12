package vip.fairy.commerceflow.simulator.payment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import vip.fairy.commerceflow.simulator.payment.domain.PaymentAdminMfaCredential;

import java.util.List;

import org.apache.ibatis.annotations.Param;

public interface PaymentAdminMfaCredentialMapper extends BaseMapper<PaymentAdminMfaCredential> {

  void deleteRecoveryCodes(long userId);


  void insertRecoveryCode(@Param("userId") long userId, @Param("hash") String hash);


  List<RecoveryCodeRow> selectUnusedRecoveryCodes(long userId);


  int consumeRecoveryCode(long id);

  record RecoveryCodeRow(long id, String codeHash) {
  }
}
