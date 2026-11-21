package vip.fairy.commerceflow.commerce.refund.infrastructure;

import java.math.BigDecimal;
import java.util.List;
import vip.fairy.commerceflow.commerce.refund.domain.LineAfterSale;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface LineAfterSaleMapper {

  LineAfterSale find(String id);

  LineAfterSale lock(String id);

  List<LineAfterSale> forCustomer(Long customerId, Long orderId);

  List<LineAfterSale> forMerchant(Long merchantId, Long orderId);

  List<LineAfterSale> forOrder(Long orderId);

  List<LineAfterSale> lockForOrder(Long orderId);

  void serializeApplication(Long orderId);

  List<LineAfterSale> due();

  void insert(LineAfterSale value);

  int customerReturn(String id, Long customerId, String trackingNumber, BigDecimal returnShippingCost);

  int merchantDecision(String id, Long merchantId, String from, String to, String decision, Integer receivedQuantity);

  int advance(String id, String from, String to, String refundNumber);
  int projectWarehouse(String id, Long warehouseId, String outboundId, String warehouseStatus,
                       Integer receivedQuantity, String to);
  int acceptWarehouse(String id, Long merchantId, String to, String note);
  void waitForWarehouse(String id);
  int approveException(String id, int quantity, BigDecimal amount, String note);
  int noteException(String id, String to, String note);

  void defer(String id, String status, String error);
}
