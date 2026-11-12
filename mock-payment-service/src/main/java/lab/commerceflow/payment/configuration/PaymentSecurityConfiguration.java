package lab.commerceflow.payment.configuration;

import tools.jackson.databind.ObjectMapper;
import lab.commerceflow.payment.security.PaymentAdminPrincipal;
import lab.commerceflow.payment.service.AuditService;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;

@Configuration
@EnableMethodSecurity
public class PaymentSecurityConfiguration {
  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
    return configuration.getAuthenticationManager();
  }

  @Bean
  SecurityContextRepository securityContextRepository() {
    return new HttpSessionSecurityContextRepository();
  }

  @Bean
  SessionAuthenticationStrategy sessionAuthenticationStrategy() {
    return new ChangeSessionIdAuthenticationStrategy();
  }

  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper mapper, AuditService audit) throws Exception {
    CookieCsrfTokenRepository csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
    csrfRepository.setCookiePath("/");
    csrfRepository.setCookieName("PAYMENT_XSRF_TOKEN");
    csrfRepository.setHeaderName("X-PAYMENT-XSRF-TOKEN");
    CsrfTokenRequestAttributeHandler requestHandler = new CsrfTokenRequestAttributeHandler();
    requestHandler.setCsrfRequestAttributeName(null);

    http
        .csrf(csrf -> csrf
            .csrfTokenRepository(csrfRepository)
            .csrfTokenRequestHandler(requestHandler)
            .ignoringRequestMatchers("/api/channel/**", "/api/checkout/**"))
        .sessionManagement(session -> session
            .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
            .maximumSessions(3))
        .authorizeHttpRequests(authorize -> authorize
            .requestMatchers("/api/auth/login", "/api/auth/csrf", "/api/channel/**", "/api/checkout/**",
                "/actuator/health", "/actuator/info").permitAll()
            .anyRequest().authenticated())
        .exceptionHandling(errors -> errors
            .authenticationEntryPoint((request, response, exception) -> {
              response.setStatus(401);
              response.setContentType(MediaType.APPLICATION_JSON_VALUE);
              mapper.writeValue(response.getOutputStream(), Map.of("message", "请先登录支付管理后台"));
            })
            .accessDeniedHandler((request, response, exception) -> {
              response.setStatus(403);
              response.setContentType(MediaType.APPLICATION_JSON_VALUE);
              mapper.writeValue(response.getOutputStream(), Map.of("message", "当前账号没有执行此操作的权限"));
            }))
        .logout(logout -> logout
            .logoutUrl("/api/auth/logout")
            .addLogoutHandler((request, response, authentication) -> {
              if (authentication != null && authentication.getPrincipal() instanceof PaymentAdminPrincipal principal) {
                audit.record(principal, "LOGOUT", "ADMIN_SESSION", null, "SUCCESS", "支付后台主动退出", request);
              }
            })
            .invalidateHttpSession(true)
            .clearAuthentication(true)
            .deleteCookies("PAYMENT_ADMIN_SESSION", "PAYMENT_XSRF_TOKEN")
            .logoutSuccessHandler((request, response, authentication) -> response.setStatus(204)))
        .httpBasic(basic -> basic.disable())
        .formLogin(form -> form.disable());
    return http.build();
  }
}
