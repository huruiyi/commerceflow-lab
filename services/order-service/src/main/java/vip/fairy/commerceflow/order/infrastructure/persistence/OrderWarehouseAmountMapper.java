package vip.fairy.commerceflow.order.infrastructure.persistence;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import vip.fairy.commerceflow.order.application.OrderWarehouseAmounts.Amount;
import vip.fairy.commerceflow.order.application.OrderWarehouseAmounts.Shipment;

@Mapper
public interface OrderWarehouseAmountMapper {

  List<Amount> orderAmounts(long orderId);
  List<Shipment> shippedLines(long orderId);
  void insert(Amount value);
}
