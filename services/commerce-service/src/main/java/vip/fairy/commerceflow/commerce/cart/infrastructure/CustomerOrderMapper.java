package vip.fairy.commerceflow.commerce.cart.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import vip.fairy.commerceflow.commerce.cart.domain.CustomerOrder;
import vip.fairy.commerceflow.commerce.cart.domain.OrderView;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

public interface CustomerOrderMapper extends BaseMapper<CustomerOrder> {

  List<OrderView> selectAllForUser(@Param("userId") Long userId);


  Optional<OrderView> selectViewForUser(@Param("orderId") Long orderId,
                                        @Param("userId") Long userId);
}
