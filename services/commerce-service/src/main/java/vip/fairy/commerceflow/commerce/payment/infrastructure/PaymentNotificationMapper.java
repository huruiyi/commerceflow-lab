package vip.fairy.commerceflow.commerce.payment.infrastructure;

import vip.fairy.commerceflow.commerce.payment.domain.PaymentView;
import org.apache.ibatis.annotations.Param;

import java.util.Optional;

public interface PaymentNotificationMapper {

  Optional<PaymentView> lockByTransaction(String transactionNo);

  void insertNotification(@Param("paymentId") Long paymentId,
                          @Param("channel") String channel,
                          @Param("notificationId") String notificationId,
                          @Param("transactionNo") String transactionNo,
                          @Param("processingStatus") String processingStatus,
                          @Param("digest") String digest,
                          @Param("reason") String reason,
                          @Param("rawStatus") String rawStatus);

  int finish(@Param("id") Long id, @Param("toStatus") String toStatus,
             @Param("reason") String reason);

  void insertEvent(@Param("paymentId") Long paymentId, @Param("eventId") String eventId,
                   @Param("type") String type, @Param("fromStatus") String fromStatus,
                   @Param("toStatus") String toStatus, @Param("detail") String detail);
}
