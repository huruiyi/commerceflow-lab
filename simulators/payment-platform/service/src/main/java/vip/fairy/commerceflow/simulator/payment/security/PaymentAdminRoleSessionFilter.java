package vip.fairy.commerceflow.simulator.payment.security;

import vip.fairy.commerceflow.simulator.payment.mapper.PaymentAdminRoleMapper;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public final class PaymentAdminRoleSessionFilter extends OncePerRequestFilter {

  private final PaymentAdminRoleMapper roles;

  public PaymentAdminRoleSessionFilter(PaymentAdminRoleMapper roles) {
    this.roles = roles;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null && authentication.getPrincipal() instanceof PaymentAdminPrincipal principal) {
      Set<String> sessionAuthorities = principal.getAuthorities().stream()
        .map(GrantedAuthority::getAuthority)
        .collect(Collectors.toSet());
      if (!sessionAuthorities.equals(Set.copyOf(roles.selectEffectivePermissions(principal.getUserId())))) {
        SecurityContextHolder.clearContext();
        HttpSession session = request.getSession(false);
        if (session != null) {
          session.invalidate();
        }
      }
    }
    chain.doFilter(request, response);
  }
}
