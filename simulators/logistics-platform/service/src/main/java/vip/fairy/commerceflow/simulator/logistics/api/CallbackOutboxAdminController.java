package vip.fairy.commerceflow.simulator.logistics.api;

import vip.fairy.commerceflow.simulator.logistics.application.CallbackOutboxService;
import vip.fairy.commerceflow.simulator.logistics.domain.CallbackOutboxView;
import vip.fairy.commerceflow.simulator.logistics.infrastructure.security.RequestSignatureVerifier;

import tools.jackson.databind.ObjectMapper;

import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/channel/callback-outbox")
public class CallbackOutboxAdminController {

  private final CallbackOutboxService outbox;
  private final RequestSignatureVerifier signatures;
  private final ObjectMapper mapper;

  public CallbackOutboxAdminController(CallbackOutboxService outbox,
                                       RequestSignatureVerifier signatures, ObjectMapper mapper) {
    this.outbox = outbox;
    this.signatures = signatures;
    this.mapper = mapper;
  }

  @GetMapping
  List<CallbackOutboxView> list(@RequestParam(required = false) String status,
                                @RequestParam(defaultValue = "100") int limit,
                                @RequestHeader("X-App-Id") String appId,
                                @RequestHeader("X-Timestamp") String timestamp,
                                @RequestHeader("X-Nonce") String nonce,
                                @RequestHeader("X-Signature") String signature) {
    signatures.verify(appId, timestamp, nonce, signature, "");
    return outbox.list(status, limit);
  }

  @GetMapping("/{id}/events")
  List<CallbackOutboxView.AuditEvent> events(@PathVariable Long id,
                                             @RequestHeader("X-App-Id") String appId,
                                             @RequestHeader("X-Timestamp") String timestamp,
                                             @RequestHeader("X-Nonce") String nonce,
                                             @RequestHeader("X-Signature") String signature) {
    signatures.verify(appId, timestamp, nonce, signature, "");
    return outbox.events(id);
  }

  @PostMapping(value = "/{id}/replay", consumes = MediaType.APPLICATION_JSON_VALUE)
  CallbackOutboxView replay(@PathVariable Long id, @RequestBody String raw,
                            @RequestHeader("X-App-Id") String appId,
                            @RequestHeader("X-Timestamp") String timestamp,
                            @RequestHeader("X-Nonce") String nonce,
                            @RequestHeader("X-Signature") String signature) throws Exception {
    signatures.verify(appId, timestamp, nonce, signature, raw);
    ReplayRequest request = mapper.readValue(raw, ReplayRequest.class);
    return outbox.replay(id, request.operatedBy(), request.reason());
  }

  public record ReplayRequest(String operatedBy, String reason) {
  }
}
