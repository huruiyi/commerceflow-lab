package vip.fairy.commerceflow.payment.application;

import org.springframework.stereotype.Service;
import vip.fairy.commerceflow.payment.infrastructure.RemoteOrderEffectClient;
import vip.fairy.commerceflow.payment.infrastructure.persistence.PaymentReportOwnershipMapper;

@Service
public class PaymentReportOwnership {

  private final PaymentReportOwnershipMapper store;
  private final RemoteOrderEffectClient orders;

  public PaymentReportOwnership(PaymentReportOwnershipMapper store, RemoteOrderEffectClient orders) {
    this.store = store;
    this.orders = orders;
  }

  public void rememberVerified(long orderId, Long merchantId) {
    if (merchantId != null) {
      store.remember(orderId, merchantId, "VERIFIED");
    }
  }

  public BackfillResult backfill(BackfillRequest request) {
    if (request == null || request.afterOrderId() < 0 || request.limit() < 1 || request.limit() > 50) {
      throw new IllegalArgumentException("Backfill requires a nonnegative cursor and a limit from 1 to 50");
    }
    var orderIds = store.orderIdsAfter(request.afterOrderId(), request.limit());
    int singleMerchant = 0;
    int missing = 0;
    int conflicting = 0;
    for (long orderId : orderIds) {
      var merchantIds = orders.itemMerchantIds(orderId);
      if (merchantIds.isEmpty()) {
        store.remember(orderId, null, "MISSING");
        missing++;
      } else if (merchantIds.size() == 1 && merchantIds.getFirst() != null && merchantIds.getFirst() > 0) {
        rememberVerified(orderId, merchantIds.getFirst());
        singleMerchant++;
      } else {
        store.remember(orderId, null, "CONFLICT");
        conflicting++;
      }
    }
    long cursor = orderIds.isEmpty() ? request.afterOrderId() : orderIds.getLast();
    return new BackfillResult(cursor, orderIds.size(), singleMerchant, missing, conflicting,
      orderIds.size() < request.limit());
  }

  public record BackfillRequest(long afterOrderId, int limit) {
  }

  public record BackfillResult(long nextAfterOrderId, int inspected, int singleMerchant,
                               int missing, int conflicting, boolean complete) {
  }
}
