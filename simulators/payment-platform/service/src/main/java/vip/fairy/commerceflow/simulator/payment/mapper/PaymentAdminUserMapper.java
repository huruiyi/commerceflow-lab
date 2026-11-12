package vip.fairy.commerceflow.simulator.payment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import vip.fairy.commerceflow.simulator.payment.domain.PaymentAdminUser;

import java.util.List;

import org.apache.ibatis.annotations.Param;

public interface PaymentAdminUserMapper extends BaseMapper<PaymentAdminUser> {

  List<String> selectRoleCodes(long userId);


  void deleteRoles(long userId);


  void insertRole(@Param("userId") long userId, @Param("roleCode") String roleCode);


  String selectMerchantScope(@Param("userId") long userId, @Param("merchantId") long merchantId);


  void deleteMerchantAccess(@Param("userId") long userId, @Param("merchantId") long merchantId);


  void insertMerchantAccess(@Param("userId") long userId, @Param("merchantId") long merchantId,
                            @Param("scope") String scope, @Param("createdBy") long createdBy);


  List<Long> selectMerchantIds(long userId);
}
