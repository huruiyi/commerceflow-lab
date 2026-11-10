package com.tanerinal.education.web;

import com.tanerinal.education.service.CurrentUserService;
import com.tanerinal.education.web.dto.LoginRequest;
import com.tanerinal.education.web.dto.LoginResponse;
import com.tanerinal.education.web.dto.UserProfileResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
  private final AuthenticationManager authenticationManager;
  private final SecurityContextRepository securityContextRepository;
  private final SessionAuthenticationStrategy sessionAuthenticationStrategy;
  private final CurrentUserService currentUsers;

  public AuthController(AuthenticationManager authenticationManager,
                        SecurityContextRepository securityContextRepository,
                        SessionAuthenticationStrategy sessionAuthenticationStrategy,
                        CurrentUserService currentUsers) {
    this.authenticationManager = authenticationManager;
    this.securityContextRepository = securityContextRepository;
    this.sessionAuthenticationStrategy = sessionAuthenticationStrategy;
    this.currentUsers = currentUsers;
  }

  @GetMapping("/csrf")
  Map<String, String> csrf(CsrfToken token) {
    return Map.of("headerName", token.getHeaderName(), "token", token.getToken());
  }

  @PostMapping("/login")
  LoginResponse login(@Valid @RequestBody LoginRequest request,
                      HttpServletRequest servletRequest,
                      HttpServletResponse servletResponse) {
    Authentication authentication = authenticationManager.authenticate(
        new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword()));
    sessionAuthenticationStrategy.onAuthentication(authentication, servletRequest, servletResponse);
    SecurityContext context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(authentication);
    SecurityContextHolder.setContext(context);
    securityContextRepository.saveContext(context, servletRequest, servletResponse);
    return new LoginResponse(UserProfileResponse.from(currentUsers.require(authentication)));
  }

  @GetMapping("/me")
  UserProfileResponse me(Authentication authentication) {
    return UserProfileResponse.from(currentUsers.require(authentication));
  }
}
