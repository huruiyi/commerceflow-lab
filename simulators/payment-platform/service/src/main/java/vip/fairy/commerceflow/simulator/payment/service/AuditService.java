package vip.fairy.commerceflow.simulator.payment.service;

import vip.fairy.commerceflow.simulator.payment.domain.PaymentAuditLog;
import vip.fairy.commerceflow.simulator.payment.mapper.PaymentAuditLogMapper;
import vip.fairy.commerceflow.simulator.payment.security.PaymentAdminPrincipal;
import jakarta.servlet.http.HttpServletRequest;

import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditService {

  private final PaymentAuditLogMapper auditLogs;

  public AuditService(PaymentAuditLogMapper auditLogs) {
    this.auditLogs = auditLogs;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void record(PaymentAdminPrincipal actor, String action, String targetType, Object targetId,
                     String result, String detail, HttpServletRequest request) {
    record(actor == null ? null : actor.getUserId(), actor == null ? "anonymous" : actor.getUsername(),
      action, targetType, targetId, result, detail, request);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
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
    LocalDateTime createdAt = LocalDateTime.now();
    log.setCreatedAt(createdAt);
    String previous = auditLogs.lockChainHead();
    log.setPreviousHash(previous);
    log.setEventHash(hash(String.join("|", previous, String.valueOf(actorId), username, action,
      targetType, String.valueOf(targetId), result, log.getDetail(), String.valueOf(log.getSourceIp()),
      createdAt.toString())));
    auditLogs.insert(log);
    auditLogs.updateChainHead(log.getEventHash());
  }

  private static String hash(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }
}
