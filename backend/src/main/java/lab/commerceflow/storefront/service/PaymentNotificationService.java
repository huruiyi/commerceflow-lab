package lab.commerceflow.storefront.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lab.commerceflow.storefront.domain.PaymentView;
import lab.commerceflow.storefront.domain.ShopOrderView;
import lab.commerceflow.storefront.mapper.ShopOrderMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PaymentNotificationService {
  private final ShopOrderMapper orders;
  private final ObjectMapper mapper;
  private final String secret;
  private final PaymentEventPipeline events;

  public PaymentNotificationService(ShopOrderMapper orders, ObjectMapper mapper,
      @Value("${commerceflow.payment-platform.callback-secret:demo-platform-callback-secret}") String secret,
      PaymentEventPipeline events) {
    this.orders = orders;
    this.mapper = mapper;
    this.secret = secret;
    this.events = events;
  }

  @Transactional
  public Result accept(String raw, String signature) {
    if (!valid(raw, signature)) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "INVALID_CALLBACK_SIGNATURE");
    }
    try {
      Notice notice = mapper.readValue(raw, Notice.class);
      PaymentView payment = orders.lockPaymentByTransaction(notice.channelTransactionNo())
          .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND"));
      String digest = HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
      if (payment.getAmount().compareTo(notice.amount()) != 0
          || !payment.getCurrency().equals(notice.currency())) {
        orders.insertNotification(payment.getId(), notice.channel(), notice.notificationId(),
            notice.channelTransactionNo(), "REJECTED", digest, "Amount or currency mismatch", notice.status());
        return new Result("REJECTED");
      }
      String target = switch (notice.status()) {
        case "SUCCESS" -> "SUCCESS";
        case "FAILED", "CLOSED", "EXPIRED" -> notice.status();
        default -> "PENDING";
      };
      if ("PENDING".equals(target)) return new Result("IGNORED");

      int changed = orders.finishPayment(payment.getId(), target,
          notice.failureReason() == null ? "" : notice.failureReason());
      if (changed == 1) {
        orders.insertPaymentEvent(payment.getId(), UUID.randomUUID().toString(), "CALLBACK_" + target,
            "PENDING", target, "Signed asynchronous notification");
        if ("SUCCESS".equals(target)) applySuccessfulPayment(payment.getOrderId());
        events.append(payment.getId().toString(), "PAYMENT_CALLBACK_" + target, raw);
      }
      orders.insertNotification(payment.getId(), notice.channel(), notice.notificationId(),
          notice.channelTransactionNo(), changed == 1 ? "PROCESSED" : "DUPLICATE_OR_FINAL", digest, "",
          notice.status());
      return new Result(changed == 1 ? "PROCESSED" : "ACKNOWLEDGED");
    } catch (DuplicateKeyException exception) {
      return new Result("ACKNOWLEDGED");
    } catch (ResponseStatusException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

  private void applySuccessfulPayment(Long orderId) {
    ShopOrderView order = orders.lockById(orderId).orElseThrow();
    Long customerId = order.getCustomerId();
    if ("PENDING_PAYMENT".equals(order.getStatus())
        && orders.updateStatus(orderId, "PENDING_PAYMENT", "PAID") == 1) {
      orders.insertEventDetailed(orderId, customerId, "PAYMENT_SUCCEEDED", "PENDING_PAYMENT", "PAID",
          "Signed asynchronous notification applied", "PAYMENT_CALLBACK", null);
    } else if ("CANCELLING".equals(order.getStatus())
        && orders.restorePaidAfterCancellationRace(orderId) == 1) {
      orders.insertEventDetailed(orderId, customerId, "CANCELLATION_LOST_PAYMENT_RACE", "CANCELLING", "PAID",
          "Payment callback won the cancellation race", "PAYMENT_CALLBACK", null);
    } else if ("CANCELLED".equals(order.getStatus())
        && orders.restorePaidAfterLateSuccess(orderId) == 1) {
      orders.insertEventDetailed(orderId, customerId, "LATE_PAYMENT_AFTER_CANCELLATION", "CANCELLED", "PAID",
          "Late successful payment restored the order; manual financial review required",
          "PAYMENT_CALLBACK", null);
    }
  }

  private boolean valid(String raw, String signature) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return signature != null && MessageDigest.isEqual(mac.doFinal(raw.getBytes(StandardCharsets.UTF_8)),
          Base64.getDecoder().decode(signature));
    } catch (Exception exception) {
      return false;
    }
  }

  public record Notice(String notificationId, String channelTransactionNo, String channel,
                       String status, BigDecimal amount, String currency, String failureReason) {}
  public record Result(String result) {}
}
