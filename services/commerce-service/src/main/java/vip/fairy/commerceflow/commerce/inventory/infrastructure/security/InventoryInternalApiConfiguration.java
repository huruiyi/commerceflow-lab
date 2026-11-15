package vip.fairy.commerceflow.commerce.inventory.infrastructure.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class InventoryInternalApiConfiguration implements WebMvcConfigurer {

  private final CatalogEventSignatureInterceptor signatures;

  public InventoryInternalApiConfiguration(CatalogEventSignatureInterceptor signatures) {
    this.signatures = signatures;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(signatures)
      .addPathPatterns("/internal/v1/inventory/catalog-events");
  }
}
