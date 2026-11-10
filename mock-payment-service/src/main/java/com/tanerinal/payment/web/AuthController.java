package com.tanerinal.payment.web;

import com.tanerinal.payment.security.PaymentAdminPrincipal;
import com.tanerinal.payment.service.AuditService;
import com.tanerinal.payment.service.LoginProtectionService;
import com.tanerinal.payment.mapper.PaymentAdminUserMapper;
import com.tanerinal.payment.domain.PaymentAdminUser;
import com.tanerinal.payment.web.dto.LoginRequest;
import com.tanerinal.payment.web.dto.SessionResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
  private final AuthenticationManager authenticationManager;
  private final SecurityContextRepository contextRepository;
  private final SessionAuthenticationStrategy sessionStrategy;
  private final LoginProtectionService protection;
  private final AuditService audit;
  private final PaymentAdminUserMapper users;

  public AuthController(AuthenticationManager authenticationManager, SecurityContextRepository contextRepository,
      SessionAuthenticationStrategy sessionStrategy, LoginProtectionService protection, AuditService audit,
      PaymentAdminUserMapper users) {
    this.authenticationManager = authenticationManager;
    this.contextRepository = contextRepository;
    this.sessionStrategy = sessionStrategy;
    this.protection = protection;
    this.audit = audit;
    this.users = users;
  }

  @GetMapping("/csrf")
  public Map<String, String> csrf(CsrfToken token) {
    return Map.of("headerName", token.getHeaderName(), "parameterName", token.getParameterName(),
        "token", token.getToken());
  }

  @PostMapping("/login")
  public SessionResponse login(@Valid @RequestBody LoginRequest body, HttpServletRequest request,
      HttpServletResponse response) {
    protection.checkRateLimit(body.username(), request.getRemoteAddr());
    try {
      Authentication authentication = authenticationManager.authenticate(
          UsernamePasswordAuthenticationToken.unauthenticated(body.username(), body.password()));
      sessionStrategy.onAuthentication(authentication, request, response);
      SecurityContext context = SecurityContextHolder.createEmptyContext();
      context.setAuthentication(authentication);
      SecurityContextHolder.setContext(context);
      contextRepository.saveContext(context, request, response);
      protection.recordSuccess(authentication.getName());
      PaymentAdminPrincipal principal = (PaymentAdminPrincipal) authentication.getPrincipal();
      audit.record(principal, "LOGIN", "ADMIN_SESSION", null, "SUCCESS", "支付后台登录成功", request);
      return session(principal);
    } catch (AuthenticationException exception) {
      protection.recordFailure(body.username());
      audit.record(null, body.username().trim().toLowerCase(), "LOGIN", "ADMIN_SESSION", null,
          "FAILURE", "用户名、密码或账号状态不正确", request);
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名、密码或账号状态不正确");
    }
  }

  @GetMapping("/session")
  public SessionResponse session(Authentication authentication) {
    if (authentication == null || !(authentication.getPrincipal() instanceof PaymentAdminPrincipal principal)) {
      throw new BadCredentialsException("未登录");
    }
    PaymentAdminUser user = users.selectById(principal.getUserId());
    List<String> authorities = principal.getAuthorities().stream().map(value -> value.getAuthority()).sorted().toList();
    return new SessionResponse(principal.getUserId(), principal.getUsername(), principal.getDisplayName(),
        principal.getEmail(), authorities, user.isPasswordChangeRequired(), user.isMfaRequired(),
        user.isMfaEnabled());
  }

  private static SessionResponse session(PaymentAdminPrincipal principal) {
    List<String> authorities = principal.getAuthorities().stream().map(value -> value.getAuthority()).sorted().toList();
    return new SessionResponse(principal.getUserId(), principal.getUsername(), principal.getDisplayName(),
        principal.getEmail(), authorities, principal.isPasswordChangeRequired(), principal.isMfaRequired(),
        principal.isMfaEnabled());
  }
}
