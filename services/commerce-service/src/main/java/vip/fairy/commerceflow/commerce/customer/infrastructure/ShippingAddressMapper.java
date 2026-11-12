package vip.fairy.commerceflow.commerce.customer.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import vip.fairy.commerceflow.commerce.customer.domain.ShippingAddress;
import org.apache.ibatis.annotations.*;

public interface ShippingAddressMapper extends BaseMapper<ShippingAddress> {

  void clearDefault(Long userId);


  void promoteFirst(Long userId);
}
