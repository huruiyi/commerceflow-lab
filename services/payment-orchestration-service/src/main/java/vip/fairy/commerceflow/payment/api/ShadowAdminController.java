package vip.fairy.commerceflow.payment.api;

import java.sql.SQLException;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vip.fairy.commerceflow.payment.application.ShadowReplicationService;
import vip.fairy.commerceflow.payment.application.ShadowReplicationService.ReplicationStatus;
import vip.fairy.commerceflow.payment.application.ShadowReplicationService.TableAudit;
import vip.fairy.commerceflow.payment.infrastructure.PaymentShadowProperties;

@RestController
@RequestMapping("/internal/v1/payment-shadow")
public class ShadowAdminController {

  private final ShadowReplicationService replication;
  private final PaymentShadowProperties properties;

  public ShadowAdminController(ShadowReplicationService replication,
                               PaymentShadowProperties properties) {
    this.replication = replication;
    this.properties = properties;
  }

  @GetMapping("/mode")
  Mode mode() {
    return new Mode(properties.acceptingTraffic(), properties.shadow().enabled());
  }

  @GetMapping("/watermark")
  ReplicationStatus watermark() throws SQLException {
    requireShadowMode();
    return replication.watermark();
  }

  @PostMapping("/snapshot")
  ReplicationStatus snapshot() throws SQLException {
    requireShadowMode();
    return replication.snapshot();
  }

  @PostMapping("/catch-up")
  ReplicationStatus catchUp() throws SQLException {
    requireShadowMode();
    return replication.catchUp();
  }

  @PostMapping("/audit")
  List<TableAudit> audit() throws SQLException {
    requireShadowMode();
    return replication.audit();
  }

  private void requireShadowMode() {
    if (!properties.shadow().enabled()) {
      throw new IllegalStateException("Payment shadow replication is disabled");
    }
  }

  public record Mode(boolean acceptingTraffic, boolean shadowEnabled) {
  }
}
