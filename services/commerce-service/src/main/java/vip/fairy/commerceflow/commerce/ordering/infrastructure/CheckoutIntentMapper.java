package vip.fairy.commerceflow.commerce.ordering.infrastructure;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CheckoutIntentMapper {
  Intent find(String quoteId);
  Intent lock(String quoteId);
  Intent lockOrder(Long orderId);
  String pending(Long customerId);
  void insert(Intent value);
  void bind(String quoteId, Long orderId, String status);
  void resolve(String quoteId, String status);
  List<String> due();
  void defer(String quoteId);

  record Intent(String quoteId, Long customerId, String scopeHash, String orderNumber,
                Long orderId, String couponId, String status, String payload) {
  }
}
