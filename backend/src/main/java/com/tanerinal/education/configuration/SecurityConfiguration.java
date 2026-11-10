package com.tanerinal.education.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tanerinal.education.repository.AppUserRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;

import java.util.Map;

@Configuration
public class SecurityConfiguration {
  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  UserDetailsService userDetailsService(AppUserRepository users) {
    return email -> users.findByEmailIgnoreCase(email)
        .map(user -> User.withUsername(user.getEmail())
            .password(user.getPasswordHash())
            .roles(user.getRole())
            .disabled(!user.isEnabled())
            .build())
        .orElseThrow(() -> new org.springframework.security.core.userdetails.UsernameNotFoundException("User not found"));
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
  SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper objectMapper) throws Exception {
    CookieCsrfTokenRepository csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
    csrfRepository.setCookiePath("/");
    csrfRepository.setHeaderName("X-XSRF-TOKEN");

    http
        .csrf(csrf -> csrf.csrfTokenRepository(csrfRepository))
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
        .authorizeRequests(authorize -> authorize
            .antMatchers("/api/auth/login", "/api/auth/csrf", "/actuator/health", "/actuator/info").permitAll()
            .anyRequest().authenticated())
        .exceptionHandling(errors -> errors
            .authenticationEntryPoint((request, response, exception) -> {
              response.setStatus(401);
              response.setContentType(MediaType.APPLICATION_JSON_VALUE);
              objectMapper.writeValue(response.getOutputStream(), Map.of("message", "Authentication required"));
            })
            .accessDeniedHandler((request, response, exception) -> {
              response.setStatus(403);
              response.setContentType(MediaType.APPLICATION_JSON_VALUE);
              objectMapper.writeValue(response.getOutputStream(), Map.of("message", "Access denied"));
            }))
        .logout(logout -> logout
            .logoutUrl("/api/auth/logout")
            .invalidateHttpSession(true)
            .clearAuthentication(true)
            .deleteCookies("JSESSIONID", "XSRF-TOKEN")
            .logoutSuccessHandler((request, response, authentication) -> response.setStatus(204)))
        .httpBasic().disable()
        .formLogin().disable();
    return http.build();
  }
}
