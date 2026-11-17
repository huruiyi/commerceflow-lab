package vip.fairy.commerceflow.payment.infrastructure;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class InternalSignatureInterceptor implements HandlerInterceptor {

  private final InternalRequestSignature signatures;
  private final JdbcTemplate jdbc;

  public InternalSignatureInterceptor(InternalRequestSignature signatures, JdbcTemplate jdbc) {
    this.signatures = signatures;
    this.jdbc = jdbc;
  }

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                           Object handler) throws Exception {
    String service = request.getHeader("X-CommerceFlow-Service");
    String timestamp = request.getHeader("X-CommerceFlow-Timestamp");
    String requestId = request.getHeader("X-CommerceFlow-Request-Id");
    String contentHash = request.getHeader("X-CommerceFlow-Content-SHA256");
    String signature = request.getHeader("X-CommerceFlow-Signature");
    if (timestamp == null || requestId == null || contentHash == null || signature == null
        || !signatures.verify(service, request.getMethod(), request.getRequestURI(), timestamp,
        requestId, contentHash, signature)) {
      response.sendError(HttpStatus.UNAUTHORIZED.value(), "Invalid internal request signature");
      return false;
    }
    jdbc.update("DELETE FROM payment_internal_request_replay "
      + "WHERE expires_at < CURRENT_TIMESTAMP(6)");
    try {
      jdbc.update("INSERT INTO payment_internal_request_replay(request_id,source_service,"
          + "content_fingerprint,expires_at) VALUES (?,?,?,CURRENT_TIMESTAMP(6) + INTERVAL 5 MINUTE)",
        requestId, service, contentHash);
    } catch (DuplicateKeyException duplicate) {
      response.sendError(HttpStatus.CONFLICT.value(), "Internal request replay rejected");
      return false;
    }
    return true;
  }
}
