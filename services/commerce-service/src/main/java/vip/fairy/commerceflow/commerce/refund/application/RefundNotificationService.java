package vip.fairy.commerceflow.commerce.refund.application;

import vip.fairy.commerceflow.commerce.inventory.application.port.InventoryCommandPort;
import vip.fairy.commerceflow.commerce.inventory.application.contract.v1.InventoryCommandTypeV1;
import vip.fairy.commerceflow.commerce.inventory.application.contract.v1.InventoryCommandV1;
import vip.fairy.commerceflow.commerce.payment.application.PaymentEventPipeline;
import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.commerce.payment.domain.PaymentView;
import vip.fairy.commerceflow.commerce.refund.domain.RefundView;
import vip.fairy.commerceflow.commerce.ordering.infrastructure.ShopOrderMapper;
import vip.fairy.commerceflow.commerce.refund.infrastructure.RefundMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RefundNotificationService implements InventoryCommandPort.StateProjection {

  private final RefundMapper refunds;
  private final ShopOrderMapper orders;
  private final ObjectMapper mapper;
  private final String secret;
  private final PaymentEventPipeline events;
  private final InventoryCommandPort inventory;

  public RefundNotificationService(
    RefundMapper refunds,
    ShopOrderMapper orders,
    ObjectMapper mapper,
    @Value("${commerceflow.payment-platform.callback-secret:demo-platform-callback-secret}") String secret,
    PaymentEventPipeline events,
    InventoryCommandPort inventory
  ) {
    this.refunds = refunds;
    this.orders = orders;
    this.mapper = mapper;
    this.secret = secret;
    this.events = events;
    this.inventory = inventory;
  }

  @Transactional
  public Result accept(String raw, String signature) {
    if (!valid(raw, signature))
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "INVALID_CALLBACK_SIGNATURE");
    try {
      Notice notice = mapper.readValue(raw, Notice.class);
      RefundView refund = refunds.lockByChannelRefundNo(notice.channelRefundNo())
        .orElseGet(() -> refunds.byOrderNumberForUpdate(notice.merchantRefundNo())
          .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "REFUND_NOT_FOUND")));
      PaymentView payment = refunds.paymentById(refund.getPaymentId()).orElseThrow();
      String digest = HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8))
      );
      if (refund.getAmount().compareTo(notice.amount()) != 0
        || !refund.getCurrency().equals(notice.currency())
        || !payment.getChannel().equals(notice.channel())) {
        refunds.insertNotification(
          refund.getId(), notice.channel(), notice.notificationId(), notice.channelRefundNo(),
          "REJECTED", digest, "Amount, currency or channel mismatch"
        );
        return new Result("REJECTED");
      }
      int changed;
      if ("SUCCEEDED".equals(notice.status())) {
        changed = refunds.succeed(refund.getId(), notice.channelRefundNo());
        if (changed == 1) {
          if (refunds.addRefundedAmount(refund.getPaymentId(), refund.getAmount()) != 1)
            throw new IllegalStateException("Refund amount exceeds payment amount");
          if (orders.updateStatus(refund.getOrderId(), "REFUNDING", "REFUNDED") != 1)
            throw new IllegalStateException("Order is not refunding");
          if (refunds.updateInventoryStatus(refund.getOrderId(), "NOT_STARTED", "WAITING") != 1)
            throw new IllegalStateException("Refund inventory state changed concurrently");
          orders.updateInventoryStatus(refund.getOrderId(), "COMMITTED", "REFUND_PENDING");
          inventory.handle(InventoryCommandPort.refund(
            notice.notificationId() + ":inventory:refund",
            refund.getOrderId(),
            notice.notificationId()
          ));
          if (refunds.updateInventoryStatus(refund.getOrderId(), "WAITING", "REFUNDED") != 1)
            throw new IllegalStateException("Refund inventory confirmation changed concurrently");
          orders.updateInventoryStatus(refund.getOrderId(), "REFUND_PENDING", "REFUNDED");
          refunds.insertEvent(
            refund.getId(), UUID.randomUUID().toString(), "REFUND_SUCCEEDED",
            "PROCESSING", "SUCCEEDED", "Signed refund notification"
          );
          orders.insertEvent(
            refund.getOrderId(), refund.getCustomerId(), "ORDER_REFUNDED",
            "REFUNDING", "REFUNDED", "Refund " + refund.getRefundNumber() + " succeeded"
          );
          events.append(refund.getId().toString(), "REFUND_SUCCEEDED", raw);
        }
      } else if ("FAILED".equals(notice.status())) {
        changed = refunds.fail(
          refund.getId(), notice.channelRefundNo(),
          notice.failureReason() == null ? "" : notice.failureReason()
        );
        if (changed == 1) {
          orders.updateStatus(refund.getOrderId(), "REFUNDING", refund.getOrderStatusBeforeRefund());
          refunds.insertEvent(
            refund.getId(), UUID.randomUUID().toString(), "REFUND_FAILED", "PROCESSING", "FAILED",
            notice.failureReason() == null ? "" : notice.failureReason()
          );
          orders.insertEvent(
            refund.getOrderId(), refund.getCustomerId(), "ORDER_REFUND_FAILED",
            "REFUNDING", refund.getOrderStatusBeforeRefund(), "Refund failed"
          );
          events.append(refund.getId().toString(), "REFUND_FAILED", raw);
        }
      } else return new Result("IGNORED");
      refunds.insertNotification(
        refund.getId(), notice.channel(), notice.notificationId(), notice.channelRefundNo(),
        changed == 1 ? "PROCESSED" : "DUPLICATE_OR_FINAL", digest,
        notice.failureReason() == null ? "" : notice.failureReason()
      );
      return new Result(changed == 1 ? "PROCESSED" : "ACKNOWLEDGED");
    } catch (DuplicateKeyException exception) {
      return new Result("ACKNOWLEDGED");
    } catch (ResponseStatusException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

  private boolean valid(String raw, String signature) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return signature != null && MessageDigest.isEqual(
        mac.doFinal(raw.getBytes(StandardCharsets.UTF_8)),
        Base64.getDecoder().decode(signature)
      );
    } catch (Exception exception) {
      return false;
    }
  }

  @Override
  public void succeeded(InventoryCommandV1 command) {
    if (command.commandType() == InventoryCommandTypeV1.REFUND) {
      requireInventoryTransition(command.orderId(), "RECONCILIATION_REQUIRED", "REFUNDED");
    }
  }

  @Override
  public void reconciliationRequired(InventoryCommandV1 command) {
    if (command.commandType() == InventoryCommandTypeV1.REFUND) {
      requireInventoryTransition(command.orderId(), "WAITING", "RECONCILIATION_REQUIRED");
    }
  }

  private void requireInventoryTransition(Long orderId, String fromStatus, String toStatus) {
    if (refunds.updateInventoryStatus(orderId, fromStatus, toStatus) != 1) {
      throw new IllegalStateException("Refund inventory recovery state changed concurrently");
    }
  }

  public record Notice(String notificationId, String merchantRefundNo, String channelRefundNo, String channel,
                       String status, BigDecimal amount, String currency, String failureReason) {
  }

  public record Result(String result) {
  }
}
