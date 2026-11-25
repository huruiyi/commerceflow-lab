package vip.fairy.commerceflow.reference.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
class PlatformAdminSecurity {

  @Bean
  SecurityFilterChain security(
    HttpSecurity http, @Value("${commerceflow.reference-data.identity-base-url}") String baseUrl
  ) throws Exception {
    var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(2)).build());
    factory.setReadTimeout(Duration.ofSeconds(3));
    var identity = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    var csrf = CookieCsrfTokenRepository.withHttpOnlyFalse();
    csrf.setCookiePath("/");
    return http
      .csrf(config -> config.csrfTokenRepository(csrf).csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
      .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
      .authorizeHttpRequests(access -> access
        .requestMatchers("/api/admin/reference-data/**").hasRole("ADMIN")
        .requestMatchers("/api/reference-data/**", "/actuator/health", "/actuator/info", "/error").permitAll()
        .anyRequest().denyAll())
      .exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, exception) ->
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED)))
      .addFilterBefore(new PlatformSession(identity), AuthorizationFilter.class)
      .build();
  }

  private record Profile(Long id, String role) {
  }

  private static final class PlatformSession extends OncePerRequestFilter {

    private final RestClient identity;

    private PlatformSession(RestClient identity) {
      this.identity = identity;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
      return !request.getServletPath().startsWith("/api/admin/reference-data/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
      String cookie = request.getHeader(HttpHeaders.COOKIE);
      if (cookie != null && !cookie.isBlank()) {
        Profile profile;
        try {
          profile = identity.get().uri("/api/auth/me").header(HttpHeaders.COOKIE, cookie)
            .retrieve().body(Profile.class);
        } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.Forbidden exception) {
          response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
          return;
        } catch (RestClientException exception) {
          response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
          return;
        }
        if (profile != null && profile.id() != null && "ADMIN".equals(profile.role())) {
          var authentication = UsernamePasswordAuthenticationToken.authenticated(profile.id(), null,
            AuthorityUtils.createAuthorityList("ROLE_ADMIN"));
          SecurityContextHolder.getContext().setAuthentication(authentication);
        } else {
          response.sendError(HttpServletResponse.SC_FORBIDDEN);
          return;
        }
      }
      chain.doFilter(request, response);
    }
  }
}
