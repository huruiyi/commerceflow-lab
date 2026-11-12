package vip.fairy.commerceflow.commerce.identity.api;

import vip.fairy.commerceflow.commerce.identity.application.CurrentUserService;
import vip.fairy.commerceflow.commerce.identity.application.LoginRateLimitService;
import vip.fairy.commerceflow.commerce.identity.api.LoginRequest;
import vip.fairy.commerceflow.commerce.identity.api.LoginResponse;
import vip.fairy.commerceflow.commerce.identity.api.UserProfileResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.authentication.RememberMeServices;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
  private final AuthenticationManager authenticationManager;
  private final SecurityContextRepository securityContextRepository;
  private final SessionAuthenticationStrategy sessionAuthenticationStrategy;
  private final CurrentUserService currentUsers;
  private final RememberMeServices rememberMeServices;
  private final LoginRateLimitService loginRateLimit;

  public AuthController(AuthenticationManager authenticationManager,
                        SecurityContextRepository securityContextRepository,
                        SessionAuthenticationStrategy sessionAuthenticationStrategy,
                        CurrentUserService currentUsers,
                        RememberMeServices rememberMeServices,
                        LoginRateLimitService loginRateLimit) {
    this.authenticationManager = authenticationManager;
    this.securityContextRepository = securityContextRepository;
    this.sessionAuthenticationStrategy = sessionAuthenticationStrategy;
    this.currentUsers = currentUsers;
    this.rememberMeServices = rememberMeServices;
    this.loginRateLimit = loginRateLimit;
  }

  @GetMapping("/csrf")
  Map<String, String> csrf(CsrfToken token) {
    return Map.of("headerName", token.getHeaderName(), "token", token.getToken());
  }

  @PostMapping("/login")
  LoginResponse login(@Valid @RequestBody LoginRequest request,
                      HttpServletRequest servletRequest,
                      HttpServletResponse servletResponse) {
    loginRateLimit.check(request.getEmail());
    Authentication authentication;
    try {
      authentication = authenticationManager.authenticate(
        new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword()));
    } catch (AuthenticationException exception) {
      loginRateLimit.failed(request.getEmail());
      throw exception;
    }
    loginRateLimit.succeeded(request.getEmail());
    sessionAuthenticationStrategy.onAuthentication(authentication, servletRequest, servletResponse);
    SecurityContext context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(authentication);
    SecurityContextHolder.setContext(context);
    securityContextRepository.saveContext(context, servletRequest, servletResponse);
    if (request.isRemember()) {
      rememberMeServices.loginSuccess(servletRequest, servletResponse, authentication);
    }
    return new LoginResponse(UserProfileResponse.from(currentUsers.require(authentication)));
  }

  @GetMapping("/me")
  UserProfileResponse me(Authentication authentication) {
    return UserProfileResponse.from(currentUsers.require(authentication));
  }
}
