package com.tanerinal.payment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tanerinal.payment.domain.PaymentAdminUser;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface PaymentAdminUserMapper extends BaseMapper<PaymentAdminUser> {
  @Select("SELECT r.role_code FROM payment_admin_role r JOIN payment_admin_user_role ur ON ur.role_id=r.id WHERE ur.user_id=#{userId} ORDER BY r.role_code")
  List<String> selectRoleCodes(long userId);

  @Delete("DELETE FROM payment_admin_user_role WHERE user_id=#{userId}")
  void deleteRoles(long userId);

  @Insert("INSERT INTO payment_admin_user_role(user_id, role_id) SELECT #{userId}, id FROM payment_admin_role WHERE role_code=#{roleCode}")
  void insertRole(@Param("userId") long userId, @Param("roleCode") String roleCode);

  @Select("SELECT permission_scope FROM payment_admin_user_merchant WHERE user_id=#{userId} AND merchant_id=#{merchantId}")
  String selectMerchantScope(@Param("userId") long userId, @Param("merchantId") long merchantId);

  @Delete("DELETE FROM payment_admin_user_merchant WHERE user_id=#{userId} AND merchant_id=#{merchantId}")
  void deleteMerchantAccess(@Param("userId") long userId, @Param("merchantId") long merchantId);

  @Insert("INSERT INTO payment_admin_user_merchant(user_id, merchant_id, permission_scope, created_by) VALUES(#{userId}, #{merchantId}, #{scope}, #{createdBy})")
  void insertMerchantAccess(@Param("userId") long userId, @Param("merchantId") long merchantId,
      @Param("scope") String scope, @Param("createdBy") long createdBy);

  @Select("SELECT merchant_id FROM payment_admin_user_merchant WHERE user_id=#{userId}")
  List<Long> selectMerchantIds(long userId);
}
