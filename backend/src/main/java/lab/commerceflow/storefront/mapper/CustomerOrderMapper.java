package lab.commerceflow.storefront.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import lab.commerceflow.storefront.domain.CustomerOrder;
import lab.commerceflow.storefront.domain.OrderView;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Optional;

public interface CustomerOrderMapper extends BaseMapper<CustomerOrder> {
  @Select("""
      SELECT o.id, o.created_at, o.note, o.quantity,
             p.name AS product_name, p.price, p.thumbnail_url
      FROM customer_orders o
      JOIN products p ON p.id = o.product_id
      WHERE o.user_id = #{userId}
      ORDER BY o.created_at DESC, o.id DESC
      """)
  List<OrderView> selectAllForUser(@Param("userId") Long userId);

  @Select("""
      SELECT o.id, o.created_at, o.note, o.quantity,
             p.name AS product_name, p.price, p.thumbnail_url
      FROM customer_orders o
      JOIN products p ON p.id = o.product_id
      WHERE o.id = #{orderId} AND o.user_id = #{userId}
      """)
  Optional<OrderView> selectViewForUser(@Param("orderId") Long orderId,
                                        @Param("userId") Long userId);
}
