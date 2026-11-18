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
    boolean commerceEndpoint = request.getRequestURI().endsWith("/commands")
      || request.getRequestURI().contains("/inventory/merchants/");
    boolean allowedService = request.getRequestURI().endsWith("/delivered-reconciliations")
      ? "commerce-service".equals(service)
      : commerceEndpoint
        ? "commerce-service".equals(service) || "payment-orchestration-service".equals(service)
        : "catalog-service".equals(service);
    if (!allowedService || timestamp == null || requestId == null || contentHash == null
        || signature == null || !signatures.verify(service, request.getMethod(),
        request.getRequestURI(), timestamp, requestId, contentHash, signature)) {
      response.sendError(HttpStatus.UNAUTHORIZED.value(), "Invalid internal request signature");
      return false;
    }
    return true;
  }
}
