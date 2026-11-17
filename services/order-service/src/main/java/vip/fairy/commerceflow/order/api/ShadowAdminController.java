package vip.fairy.commerceflow.order.api;

import java.sql.SQLException;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vip.fairy.commerceflow.order.application.ShadowReplicationService;
import vip.fairy.commerceflow.order.application.ShadowReplicationService.ReplicationStatus;
import vip.fairy.commerceflow.order.application.ShadowReplicationService.TableAudit;
import vip.fairy.commerceflow.order.infrastructure.OrderShadowProperties;

@RestController
@RequestMapping("/internal/v1/order-shadow")
public class ShadowAdminController {

  private final ShadowReplicationService replication;
  private final OrderShadowProperties properties;

  public ShadowAdminController(ShadowReplicationService replication,
                               OrderShadowProperties properties) {
    this.replication = replication;
    this.properties = properties;
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
      throw new IllegalStateException("Order shadow replication is disabled after cutover");
    }
  }
}
