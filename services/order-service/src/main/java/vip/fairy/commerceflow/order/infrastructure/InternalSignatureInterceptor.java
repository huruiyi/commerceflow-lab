package vip.fairy.commerceflow.order.infrastructure;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import vip.fairy.commerceflow.order.infrastructure.persistence.OrderRequestReplayMapper;

@Component
public class InternalSignatureInterceptor implements HandlerInterceptor {

  private final InternalRequestSignature signatures;
  private final OrderRequestReplayMapper replays;

  public InternalSignatureInterceptor(InternalRequestSignature signatures, OrderRequestReplayMapper replays) {
    this.signatures = signatures;
    this.replays = replays;
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
    replays.deleteExpired();
    try {
      replays.insert(requestId, service, contentHash);
    } catch (DuplicateKeyException duplicate) {
      response.sendError(HttpStatus.CONFLICT.value(), "Internal request replay rejected");
      return false;
    }
    return true;
  }
}
