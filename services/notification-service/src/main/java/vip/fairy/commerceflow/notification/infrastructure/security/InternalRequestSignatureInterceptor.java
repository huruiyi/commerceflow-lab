package vip.fairy.commerceflow.notification.infrastructure.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class InternalRequestSignatureInterceptor implements HandlerInterceptor {

  private final InternalRequestSignature signatures;

  public InternalRequestSignatureInterceptor(InternalRequestSignature signatures) {
    this.signatures = signatures;
  }

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                           Object handler) throws Exception {
    String service = request.getHeader(InternalRequestSignature.SERVICE_HEADER);
    String timestamp = request.getHeader(InternalRequestSignature.TIMESTAMP_HEADER);
    String requestId = request.getHeader(InternalRequestSignature.REQUEST_ID_HEADER);
    String signature = request.getHeader(InternalRequestSignature.SIGNATURE_HEADER);
    String query = request.getQueryString();
    String target = request.getRequestURI() + (query == null ? "" : "?" + query);
    if (!"commerce-service".equals(service) || timestamp == null || requestId == null
        || signature == null || !signatures.verify(request.getMethod(), target, timestamp,
        requestId, signature)) {
      response.sendError(HttpStatus.UNAUTHORIZED.value(), "Invalid internal service signature");
      return false;
    }
    return true;
  }
}
