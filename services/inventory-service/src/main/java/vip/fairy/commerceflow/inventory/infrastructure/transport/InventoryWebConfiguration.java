package vip.fairy.commerceflow.inventory.infrastructure.transport;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class InventoryWebConfiguration implements WebMvcConfigurer {

  private final InternalSignatureInterceptor signatures;

  public InventoryWebConfiguration(InternalSignatureInterceptor signatures) {
    this.signatures = signatures;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(signatures).addPathPatterns("/internal/v1/inventory/**");
  }
}
