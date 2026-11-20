package vip.fairy.commerceflow.notification.infrastructure.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class InternalApiSecurityConfiguration implements WebMvcConfigurer {

  private final InternalRequestSignatureInterceptor signatures;

  public InternalApiSecurityConfiguration(InternalRequestSignatureInterceptor signatures) {
    this.signatures = signatures;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(signatures).addPathPatterns("/internal/v1/**")
      .excludePathPatterns("/internal/v1/offsite-notifications");
  }
}
