package vip.fairy.commerceflow.payment.api;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.payment.application.RemotePaymentProtocolService;
import vip.fairy.commerceflow.payment.application.RemotePaymentProtocolService.CallbackReceipt;
import vip.fairy.commerceflow.payment.application.RemotePaymentProtocolService.PaymentNotice;
import vip.fairy.commerceflow.payment.application.RemotePaymentProtocolService.RefundNotice;
import vip.fairy.commerceflow.payment.infrastructure.ChannelCallbackSignature;
import vip.fairy.commerceflow.payment.infrastructure.PaymentShadowProperties;

@RestController
@RequestMapping("/api")
public class ChannelCallbackController {

  private final PaymentShadowProperties properties;
  private final ChannelCallbackSignature signatures;
  private final RemotePaymentProtocolService protocol;
  private final ObjectMapper mapper;

  public ChannelCallbackController(PaymentShadowProperties properties,
                                   ChannelCallbackSignature signatures,
                                   RemotePaymentProtocolService protocol,
                                   ObjectMapper mapper) {
    this.properties = properties;
    this.signatures = signatures;
    this.protocol = protocol;
    this.mapper = mapper;
  }

  @PostMapping("/payment-notifications/callback")
  CallbackReceipt payment(@RequestBody String payload,
                          @RequestHeader("X-Callback-Signature") String signature,
                          @RequestHeader(value = "traceparent", required = false) String traceparent) {
    requireTrafficAndSignature(payload, signature);
    PaymentPayload value = mapper.readValue(payload, PaymentPayload.class);
    return protocol.paymentCallback(new PaymentNotice(value.notificationId(),
      value.channelTransactionNo(), value.channel(), value.status(), value.amount(), value.currency(),
      value.failureReason(), trace(traceparent, value.notificationId())), payload);
  }

  @PostMapping("/refund-notifications/callback")
  CallbackReceipt refund(@RequestBody String payload,
                         @RequestHeader("X-Callback-Signature") String signature,
                         @RequestHeader(value = "traceparent", required = false) String traceparent) {
    requireTrafficAndSignature(payload, signature);
    RefundPayload value = mapper.readValue(payload, RefundPayload.class);
    return protocol.refundCallback(new RefundNotice(value.notificationId(), value.merchantRefundNo(),
      value.channelRefundNo(), value.channel(), value.status(), value.amount(), value.currency(),
      value.failureReason(), trace(traceparent, value.notificationId())), payload);
  }

  private void requireTrafficAndSignature(String payload, String signature) {
    if (!properties.acceptingTraffic()) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
        "Payment cutover gate is closed; callbacks remain on the current owner");
    }
    if (!signatures.valid(payload, signature)) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "INVALID_CALLBACK_SIGNATURE");
    }
  }

  private String trace(String supplied, String seed) {
    if (supplied != null && supplied.matches("^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$")) {
      return supplied;
    }
    try {
      String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(seed.getBytes(StandardCharsets.UTF_8)));
      return "00-" + hash.substring(0, 32) + "-" + hash.substring(32, 48) + "-01";
    } catch (Exception exception) {
      throw new IllegalStateException("Could not create callback trace context", exception);
    }
  }

  record PaymentPayload(String notificationId, String channelTransactionNo, String channel,
                        String status, BigDecimal amount, String currency, String failureReason) {
  }

  record RefundPayload(String notificationId, String merchantRefundNo, String channelRefundNo,
                       String channel, String status, BigDecimal amount, String currency,
                       String failureReason) {
  }
}
