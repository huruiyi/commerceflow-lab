package vip.fairy.commerceflow.simulator.payment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import vip.fairy.commerceflow.simulator.payment.domain.PaymentAdminRole;

import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface PaymentAdminRoleMapper extends BaseMapper<PaymentAdminRole> {
  PaymentAdminRole lockRole(long roleId);
  List<String> selectPermissions(long roleId);
  void deletePermissions(long roleId);
  void insertPermission(@Param("roleId") long roleId, @Param("permission") String permission);
  List<RoleAccount> selectAccounts(long roleId);
  List<RoleBinding> selectBindings(long userId);
  List<String> selectEffectivePermissions(long userId);
  List<Long> selectAuthorizedMerchants(@Param("userId") long userId, @Param("permission") String permission);
  void savePlatformBinding(@Param("userId") long userId, @Param("roleId") long roleId,
                           @Param("enabled") boolean enabled);
  void deletePlatformBinding(@Param("userId") long userId, @Param("roleId") long roleId);
  void saveMerchantBinding(@Param("userId") long userId, @Param("merchantId") long merchantId,
                           @Param("roleId") long roleId, @Param("enabled") boolean enabled);
  void deleteMerchantBinding(@Param("userId") long userId, @Param("merchantId") long merchantId,
                             @Param("roleId") long roleId);

  record RoleAccount(long userId, String username, Long merchantId, String merchantName, boolean enabled) {
  }

  record RoleBinding(long roleId, String roleCode, String roleName, String roleScope, Long merchantId,
                     String merchantName, boolean enabled, boolean roleEnabled) {
  }
}
