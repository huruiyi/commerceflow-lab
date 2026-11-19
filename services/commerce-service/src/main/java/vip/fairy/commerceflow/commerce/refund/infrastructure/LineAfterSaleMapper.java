package vip.fairy.commerceflow.commerce.refund.infrastructure;

import java.math.BigDecimal;
import java.util.List;
import vip.fairy.commerceflow.commerce.refund.domain.LineAfterSale;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface LineAfterSaleMapper {

  LineAfterSale find(String id);

  List<LineAfterSale> forCustomer(Long customerId, Long orderId);

  List<LineAfterSale> forMerchant(Long merchantId, Long orderId);

  List<LineAfterSale> lockForOrder(Long orderId);

  List<LineAfterSale> due();

  void insert(LineAfterSale value);

  int customerReturn(String id, Long customerId, String trackingNumber, BigDecimal returnShippingCost);

  int merchantDecision(String id, Long merchantId, String from, String to, String decision, Integer receivedQuantity);

  int advance(String id, String from, String to, String refundNumber);

  void defer(String id, String status, String error);
}
