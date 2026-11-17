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

@RestController
@RequestMapping("/internal/v1/order-shadow")
public class ShadowAdminController {

  private final ShadowReplicationService replication;

  public ShadowAdminController(ShadowReplicationService replication) {
    this.replication = replication;
  }

  @GetMapping("/watermark")
  ReplicationStatus watermark() throws SQLException {
    return replication.watermark();
  }

  @PostMapping("/snapshot")
  ReplicationStatus snapshot() throws SQLException {
    return replication.snapshot();
  }

  @PostMapping("/catch-up")
  ReplicationStatus catchUp() throws SQLException {
    return replication.catchUp();
  }

  @PostMapping("/audit")
  List<TableAudit> audit() throws SQLException {
    return replication.audit();
  }
}
