package com.tanerinal.payment.service;

import com.tanerinal.payment.domain.PaymentAuditLog;
import com.tanerinal.payment.mapper.PaymentAuditLogMapper;
import com.tanerinal.payment.security.PaymentAdminPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;

@Service
public class AuditService {
  private final PaymentAuditLogMapper auditLogs;

  public AuditService(PaymentAuditLogMapper auditLogs) {
    this.auditLogs = auditLogs;
  }

  public void record(PaymentAdminPrincipal actor, String action, String targetType, Object targetId,
      String result, String detail, HttpServletRequest request) {
    record(actor == null ? null : actor.getUserId(), actor == null ? "anonymous" : actor.getUsername(),
        action, targetType, targetId, result, detail, request);
  }

  public void record(Long actorId, String username, String action, String targetType, Object targetId,
      String result, String detail, HttpServletRequest request) {
    PaymentAuditLog log = new PaymentAuditLog();
    log.setActorUserId(actorId);
    log.setActorUsername(username);
    log.setAction(action);
    log.setTargetType(targetType);
    log.setTargetId(targetId == null ? null : targetId.toString());
    log.setResult(result);
    log.setDetail(detail.length() > 500 ? detail.substring(0, 500) : detail);
    log.setSourceIp(request == null ? null : request.getRemoteAddr());
    log.setCreatedAt(LocalDateTime.now());
    auditLogs.insert(log);
  }
}
