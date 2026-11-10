package com.tanerinal.education.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tanerinal.education.domain.ShippingAddress;
import org.apache.ibatis.annotations.*;

public interface ShippingAddressMapper extends BaseMapper<ShippingAddress> {
  @Update("UPDATE shipping_addresses SET is_default=FALSE,updated_at=CURRENT_TIMESTAMP WHERE user_id=#{userId}")
  void clearDefault(Long userId);

  @Update("UPDATE shipping_addresses SET is_default=TRUE,updated_at=CURRENT_TIMESTAMP WHERE id=(SELECT id FROM (SELECT id FROM shipping_addresses WHERE user_id=#{userId} ORDER BY id LIMIT 1) first_address)")
  void promoteFirst(Long userId);
}
