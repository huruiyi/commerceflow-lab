package vip.fairy.commerceflow.notification.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
class NotificationWritePauseFilter extends OncePerRequestFilter {

  private final NotificationWritePause pause;

  NotificationWritePauseFilter(NotificationWritePause pause) {
    this.pause = pause;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    if (!request.getServletPath().startsWith("/internal/v1/")) {
      return true;
    }
    return switch (request.getMethod()) {
      case "POST", "PATCH", "PUT", "DELETE" -> false;
      default -> true;
    };
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (pause.paused()) {
      response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
      return;
    }
    chain.doFilter(request, response);
  }
}
