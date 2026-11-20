package vip.fairy.commerceflow.messaging;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Service
public class DeliveryLedger {

  private static final Set<String> CHANNELS = Set.of("SMS", "EMAIL");
  private static final Set<String> STATES = Set.of("ACCEPTED", "DELIVERED", "FAILED");
  private static final Set<String> FAULTS = Set.of("THROTTLE_ONCE", "TIMEOUT_AFTER_ACCEPT", "DELIVERY_FAILED");
  private static final Pattern SMS = Pattern.compile("\\+1[2-9][0-9]{2}55501[0-9]{2}");
  private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._+-]{1,64}@[A-Za-z0-9-]{1,40}\\.test");

  private final MessagingMapper store;
  private final ObjectMapper json;
  private final boolean faultsEnabled;

  DeliveryLedger(MessagingMapper store, ObjectMapper json,
                 @Value("${messaging.faults-enabled}") boolean faultsEnabled) {
    this.store = store;
    this.json = json;
    this.faultsEnabled = faultsEnabled;
  }

  @Transactional
  public Submission submit(Command value) {
    if (value == null) throw invalid();
    requireId(value.requestId());
    requireChannel(value.channel());
    boolean sms = "SMS".equals(value.channel());
    if (value.recipient() == null || !(sms ? SMS : EMAIL).matcher(value.recipient()).matches()
        || value.reference() == null || value.reference().isBlank() || value.reference().length() > 120
        || value.message() == null || value.message().isBlank() || value.message().length() > (sms ? 320 : 4000)
        || (sms ? value.subject() != null : value.subject() == null
          || value.subject().isBlank() || value.subject().length() > 160)) throw invalid();

    String fingerprint = MessagingAccess.digest(json.writeValueAsString(value));
    var prior = store.find(value.requestId());
    if (prior != null) return replay(prior, fingerprint);
    String behavior = faultsEnabled ? store.fault(value.requestId()) : null;
    if ("THROTTLE_ONCE".equals(behavior) && store.consumeThrottle(value.requestId()) == 1) {
      return new Submission(null, "THROTTLED");
    }
    String id = UUID.randomUUID().toString();
    String outcome = "DELIVERY_FAILED".equals(behavior) ? "FAILED" : "DELIVERED";
    String recipientMask = sms ? "+1••• ••• " + value.recipient().substring(value.recipient().length() - 4)
      : value.recipient().charAt(0) + "***@" + value.recipient().split("@", 2)[1];
    store.insert(new Stored(value.requestId(), id, value.channel(), recipientMask, value.reference(),
      null, null, null, fingerprint, outcome));
    var result = store.lock(value.requestId());
    if (!id.equals(result.providerId())) return replay(result, fingerprint);
    return new Submission(result.receipt(), "TIMEOUT_AFTER_ACCEPT".equals(behavior) ? "TIMEOUT" : "OK");
  }

  public Receipt query(String id) {
    requireId(id);
    var stored = store.find(id);
    if (stored == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Delivery not found");
    return stored.receipt();
  }

  public Page list(String channel, String status, String reference, int page) {
    requireChannel(channel);
    if ((status != null && !STATES.contains(status)) || page < 1 || page > 10000
        || (reference != null && reference.length() > 120)) throw invalid();
    return new Page(store.list(channel, status, reference, (page - 1) * 20),
      store.count(channel, status, reference), page);
  }

  public void setFault(String id, String behavior) {
    if (!faultsEnabled) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Fault injection is disabled");
    requireId(id);
    if (behavior == null || !FAULTS.contains(behavior)) throw invalid();
    if (store.find(id) != null) throw conflict();
    try {
      store.setFault(id, behavior);
    } catch (DuplicateKeyException failure) {
      if (!behavior.equals(store.fault(id))) throw conflict();
    }
  }

  @Scheduled(fixedDelay = 1000)
  public void settleDeliveries() {
    store.finishDue();
  }

  private Submission replay(Stored prior, String fingerprint) {
    if (!fingerprint.equals(prior.fingerprint())) throw conflict();
    return new Submission(prior.receipt(), "OK");
  }

  private static void requireId(String value) {
    try {
      if (value != null && UUID.fromString(value).toString().equals(value)) return;
    } catch (IllegalArgumentException failure) {
      throw invalid();
    }
    throw invalid();
  }

  private static void requireChannel(String channel) {
    if (channel == null || !CHANNELS.contains(channel)) throw invalid();
  }

  private static ResponseStatusException invalid() {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid channel, simulation recipient or input");
  }

  private static ResponseStatusException conflict() {
    return new ResponseStatusException(HttpStatus.CONFLICT, "Request ID already has different facts");
  }

  public record Command(String requestId, String channel, String recipient, String reference,
                        String subject, String message) {
  }

  public record Submission(Receipt receipt, String result) {
  }

  public record Page(List<Receipt> entries, int total, int page) {
  }

  public record Receipt(String requestId, String providerId, String channel, String recipientMask,
                        String reference, String status, Instant acceptedAt, Instant finishedAt) {
  }

  public record Stored(String requestId, String providerId, String channel, String recipientMask,
                       String reference, String status, Instant acceptedAt, Instant finishedAt,
                       String fingerprint, String outcome) {
    Receipt receipt() {
      return new Receipt(requestId, providerId, channel, recipientMask, reference, status, acceptedAt, finishedAt);
    }
  }
}
