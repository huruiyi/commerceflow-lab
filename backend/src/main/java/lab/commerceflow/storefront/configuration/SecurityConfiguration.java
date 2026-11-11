package lab.commerceflow.storefront.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lab.commerceflow.storefront.domain.AppUser;
import lab.commerceflow.storefront.mapper.AppUserMapper;
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
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.authentication.RememberMeServices;
import org.springframework.security.web.authentication.rememberme.JdbcTokenRepositoryImpl;
import org.springframework.security.web.authentication.rememberme.PersistentTokenBasedRememberMeServices;
import org.springframework.security.web.authentication.rememberme.PersistentTokenRepository;

import javax.sql.DataSource;

import java.util.Map;

@Configuration
public class SecurityConfiguration {
  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  UserDetailsService userDetailsService(AppUserMapper users) {
    return email -> {
      AppUser user = users.selectOne(new LambdaQueryWrapper<AppUser>()
          .eq(AppUser::getEmail, email.toLowerCase()));
      if (user == null) {
        throw new org.springframework.security.core.userdetails.UsernameNotFoundException("User not found");
      }
      String[] roles = users.selectRoleCodes(user.getId()).toArray(String[]::new);
      return User.withUsername(user.getEmail())
            .password(user.getPasswordHash())
            .roles(roles)
            .disabled(!user.isEnabled())
            .build();
    };
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
  PersistentTokenRepository persistentTokenRepository(DataSource dataSource) {
    JdbcTokenRepositoryImpl repository = new JdbcTokenRepositoryImpl();
    repository.setDataSource(dataSource);
    return repository;
  }

  @Bean
  RememberMeServices rememberMeServices(UserDetailsService userDetailsService,
                                        PersistentTokenRepository tokenRepository,
                                        @org.springframework.beans.factory.annotation.Value("${security.remember-me.key}") String key,
                                        @org.springframework.beans.factory.annotation.Value("${security.remember-me.validity-seconds}") int validitySeconds,
                                        @org.springframework.beans.factory.annotation.Value("${server.servlet.session.cookie.secure:false}") boolean secureCookie) {
    PersistentTokenBasedRememberMeServices services =
        new PersistentTokenBasedRememberMeServices(key, userDetailsService, tokenRepository);
    services.setTokenValiditySeconds(validitySeconds);
    services.setCookieName("COMMERCEFLOW_REMEMBER_ME");
    services.setUseSecureCookie(secureCookie);
    services.setAlwaysRemember(true);
    return services;
  }

  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper objectMapper,
                                          RememberMeServices rememberMeServices) throws Exception {
    CookieCsrfTokenRepository csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
    csrfRepository.setCookiePath("/");
    csrfRepository.setHeaderName("X-XSRF-TOKEN");
    CsrfTokenRequestAttributeHandler csrfRequestHandler = new CsrfTokenRequestAttributeHandler();
    csrfRequestHandler.setCsrfRequestAttributeName(null);

    http
        .csrf(csrf -> csrf
            .csrfTokenRepository(csrfRepository)
            .csrfTokenRequestHandler(csrfRequestHandler)
            .ignoringRequestMatchers("/api/payment-notifications/**", "/api/refund-notifications/**"))
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
        .rememberMe(remember -> remember.rememberMeServices(rememberMeServices))
        .authorizeHttpRequests(authorize -> authorize
            .requestMatchers("/api/auth/login", "/api/auth/csrf", "/api/products/**", "/api/payment-notifications/**", "/api/refund-notifications/**", "/actuator/health", "/actuator/info", "/actuator/prometheus").permitAll()
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
            .deleteCookies("SESSION", "JSESSIONID", "XSRF-TOKEN", "COMMERCEFLOW_REMEMBER_ME")
            .logoutSuccessHandler((request, response, authentication) -> response.setStatus(204)))
        .httpBasic(basic -> basic.disable())
        .formLogin(form -> form.disable());
    return http.build();
  }
}
