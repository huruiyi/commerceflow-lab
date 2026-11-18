package vip.fairy.commerceflow.payment.infrastructure;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import vip.fairy.commerceflow.payment.infrastructure.persistence.PaymentRequestReplayMapper;

@Component
public class InternalSignatureInterceptor implements HandlerInterceptor {

  private final InternalRequestSignature signatures;
  private final PaymentRequestReplayMapper store;

  public InternalSignatureInterceptor(InternalRequestSignature signatures, PaymentRequestReplayMapper store) {
    this.signatures = signatures;
    this.store = store;
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
    store.deleteExpired();
    try {
      store.insertRequest(requestId, service, contentHash);
    } catch (DuplicateKeyException duplicate) {
      response.sendError(HttpStatus.CONFLICT.value(), "Internal request replay rejected");
      return false;
    }
    return true;
  }
}
