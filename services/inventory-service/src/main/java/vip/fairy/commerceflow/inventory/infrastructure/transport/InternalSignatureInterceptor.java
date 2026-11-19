package vip.fairy.commerceflow.inventory.infrastructure.transport;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class InternalSignatureInterceptor implements HandlerInterceptor {

  private final InternalRequestSignature signatures;

  public InternalSignatureInterceptor(InternalRequestSignature signatures) {
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
    boolean allowedService = allowedService(request.getRequestURI(), service);
    if (!allowedService || timestamp == null || requestId == null || contentHash == null
        || signature == null || !signatures.verify(service, request.getMethod(),
        request.getRequestURI(), timestamp, requestId, contentHash, signature)) {
      response.sendError(HttpStatus.UNAUTHORIZED.value(), "Invalid internal request signature");
      return false;
    }
    return true;
  }

  private boolean allowedService(String path, String service) {
    if (path.endsWith("/outbound-documents")) return "fulfillment-service".equals(service);
    if (path.endsWith("/line-credits")
        || path.endsWith("/delivered-reconciliations")
        || path.contains("/inventory/stocktakes")
        || path.startsWith("/internal/v1/inventory/reservations/")
        || path.equals("/internal/v1/inventory/availability")) {
      return "commerce-service".equals(service);
    }
    if (path.endsWith("/commands") || path.contains("/inventory/merchants/")) {
      return "commerce-service".equals(service) || "payment-orchestration-service".equals(service);
    }
    return "catalog-service".equals(service);
  }
}
