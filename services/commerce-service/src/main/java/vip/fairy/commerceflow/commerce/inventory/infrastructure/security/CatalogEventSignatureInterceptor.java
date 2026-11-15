package vip.fairy.commerceflow.commerce.inventory.infrastructure.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class CatalogEventSignatureInterceptor implements HandlerInterceptor {

  private final CatalogEventRequestSignature signatures;

  public CatalogEventSignatureInterceptor(CatalogEventRequestSignature signatures) {
    this.signatures = signatures;
  }

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                           Object handler) throws Exception {
    String service = request.getHeader("X-CommerceFlow-Service");
    String timestamp = request.getHeader("X-CommerceFlow-Timestamp");
    String requestId = request.getHeader("X-CommerceFlow-Request-Id");
    String contentHash = request.getHeader("X-CommerceFlow-Content-SHA256");
    String signature = request.getHeader("X-CommerceFlow-Signature");
    if (!"catalog-service".equals(service) || timestamp == null || requestId == null
        || contentHash == null || signature == null
        || !signatures.verify(request.getMethod(), request.getRequestURI(), timestamp, requestId,
        contentHash, signature)) {
      response.sendError(HttpStatus.UNAUTHORIZED.value(), "Invalid catalog event signature");
      return false;
    }
    return true;
  }
}
