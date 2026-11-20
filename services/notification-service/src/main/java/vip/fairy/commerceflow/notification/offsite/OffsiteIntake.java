package vip.fairy.commerceflow.notification.offsite;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@RestController
class OffsiteIntake {

  private static final Pattern REFERENCE = Pattern.compile("[A-Za-z0-9_-]{1,100}");
  private final ObjectMapper json;
  private final OffsiteQueue queue;
  private final String commerceSecret;
  private final String paymentSecret;

  OffsiteIntake(ObjectMapper json, OffsiteQueue queue,
      @Value("${commerceflow.offsite.commerce-secret:demo-offsite-commerce-secret}") String commerceSecret,
      @Value("${commerceflow.offsite.payment-secret:demo-offsite-payment-secret}") String paymentSecret) {
    if (commerceSecret.isBlank() || paymentSecret.isBlank() || commerceSecret.equals(paymentSecret)) {
      throw new IllegalArgumentException("Distinct notification intake credentials are required");
    }
    this.json = json;
    this.queue = queue;
    this.commerceSecret = commerceSecret;
    this.paymentSecret = paymentSecret;
  }

  @PostMapping("/internal/v1/offsite-notifications")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void accept(HttpServletRequest request, @RequestBody String body) {
    String source = authenticate(request, body);
    Event event;
    try {
      event = json.readValue(body, Event.class);
    } catch (JacksonException failure) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid notification event");
    }
    if (event == null || !source.equals(event.type())) throw denied();
    if (!event.valid()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid notification facts");
    queue.enqueue(event);
  }

  private String authenticate(HttpServletRequest request, String body) {
    String time = request.getHeader("X-CommerceFlow-Timestamp");
    String nonce = request.getHeader("X-CommerceFlow-Request-Id");
    try {
      long now = Instant.now().getEpochSecond();
      long epoch = Long.parseLong(time);
      if (epoch < now - 120 || epoch > now + 120 || nonce == null
          || !UUID.fromString(nonce).toString().equals(nonce)) throw denied();
    } catch (IllegalArgumentException failure) {
      throw denied();
    }
    String hash = OffsiteSignature.digest(body);
    if (!equal(hash, request.getHeader("X-CommerceFlow-Content-SHA256"))) throw denied();
    if (matches(request, commerceSecret, time, nonce, hash)) return "RETURN_WORK_OPENED";
    if (matches(request, paymentSecret, time, nonce, hash)) return "REFUND_SUCCEEDED";
    throw denied();
  }

  private boolean matches(HttpServletRequest request, String secret, String time, String nonce, String hash) {
    return equal(OffsiteSignature.sign(secret, request.getMethod(), request.getRequestURI(), time, nonce, hash),
      request.getHeader("X-CommerceFlow-Signature"));
  }

  private static boolean equal(String expected, String received) {
    return received != null
      && MessageDigest.isEqual(OffsiteSignature.bytes(expected), OffsiteSignature.bytes(received));
  }

  private static ResponseStatusException denied() {
    return new ResponseStatusException(HttpStatus.UNAUTHORIZED);
  }

  record Event(int version, String eventId, String type, long recipientId, long orderId,
               String reference, BigDecimal amount, String currency) {

    boolean refund() {
      return "REFUND_SUCCEEDED".equals(type);
    }

    boolean valid() {
      if (version != 1 || recipientId < 1 || orderId < 1 || reference == null
          || !REFERENCE.matcher(reference).matches()) return false;
      String prefix = refund() ? "offsite-refund:" : "offsite-work:";
      if (!(prefix + reference).equals(eventId)) return false;
      return refund() ? amount != null && amount.signum() > 0 && amount.scale() <= 2
        && amount.precision() <= 20 && currency != null && currency.matches("[A-Z]{3}")
        : amount == null && currency == null;
    }
  }
}
