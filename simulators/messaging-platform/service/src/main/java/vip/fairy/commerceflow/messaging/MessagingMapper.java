package vip.fairy.commerceflow.messaging;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;

@Mapper
interface MessagingMapper {

  DeliveryLedger.Stored find(String requestId);
  DeliveryLedger.Stored lock(String requestId);
  void insert(DeliveryLedger.Stored value);
  String fault(String requestId);
  int consumeThrottle(String requestId);
  void setFault(String requestId, String behavior);
  int finishDue();
  List<DeliveryLedger.Receipt> list(String channel, String status, String reference, int offset);
  int count(String channel, String status, String reference);
}
