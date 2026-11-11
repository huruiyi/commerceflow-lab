package lab.commerceflow.payment.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

@Component
public class SignedRequestFilter extends OncePerRequestFilter {
  private final RequestSignatureService signatures;
  private final ObjectMapper mapper;

  public SignedRequestFilter(RequestSignatureService signatures, ObjectMapper mapper) {
    this.signatures = signatures;
    this.mapper = mapper;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().startsWith("/api/channel/");
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain filterChain) throws ServletException, IOException {
    CachedBodyRequest cached = new CachedBodyRequest(request);
    String target = request.getRequestURI() +
        (request.getQueryString() == null ? "" : "?" + request.getQueryString());
    RequestSignatureService.Headers headers = new RequestSignatureService.Headers(
        request.getHeader("X-App-Id"), request.getHeader("X-Signature-Algorithm"),
        request.getHeader("X-Timestamp"), request.getHeader("X-Nonce"),
        request.getHeader("X-Request-Id"), request.getHeader("X-Body-SHA256"),
        request.getHeader("X-Signature"));
    try {
      SignedRequestContext context = signatures.verify(request.getMethod(), target, cached.body(), headers);
      cached.setAttribute(SignedRequestContext.ATTRIBUTE, context);
      filterChain.doFilter(cached, response);
    } catch (ResponseStatusException exception) {
      response.setStatus(exception.getStatusCode().value());
      response.setContentType(MediaType.APPLICATION_JSON_VALUE);
      mapper.writeValue(response.getOutputStream(), Map.of("message", exception.getReason()));
    } catch (Exception exception) {
      response.setStatus(503);
      response.setContentType(MediaType.APPLICATION_JSON_VALUE);
      mapper.writeValue(response.getOutputStream(), Map.of(
          "message", "签名安全服务暂不可用，请稍后重试（安全策略为失败关闭）"));
    }
  }
}
