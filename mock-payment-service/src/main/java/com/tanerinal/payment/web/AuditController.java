package com.tanerinal.payment.web;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tanerinal.payment.domain.PaymentAuditLog;
import com.tanerinal.payment.mapper.PaymentAuditLogMapper;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/audit-logs")
@PreAuthorize("hasAuthority('AUDIT_READ')")
public class AuditController {
  private final PaymentAuditLogMapper auditLogs;

  public AuditController(PaymentAuditLogMapper auditLogs) {
    this.auditLogs = auditLogs;
  }

  @GetMapping
  public List<PaymentAuditLog> list() {
    return auditLogs.selectList(new LambdaQueryWrapper<PaymentAuditLog>()
        .orderByDesc(PaymentAuditLog::getId).last("LIMIT 200"));
  }
}
