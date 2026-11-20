package vip.fairy.commerceflow.simulator.payment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import vip.fairy.commerceflow.simulator.payment.domain.PaymentAdminUser;

import java.util.List;

import org.apache.ibatis.annotations.Param;

public interface PaymentAdminUserMapper extends BaseMapper<PaymentAdminUser> {

  List<String> selectRoleCodes(long userId);

  List<Long> lockAccountIds(@Param("actorId") long actorId, @Param("userId") long userId);
}
