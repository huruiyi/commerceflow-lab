package vip.fairy.commerceflow.payment.infrastructure;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfiguration implements WebMvcConfigurer {

  private final InternalSignatureInterceptor internalSignature;

  public WebConfiguration(InternalSignatureInterceptor internalSignature) {
    this.internalSignature = internalSignature;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(internalSignature).addPathPatterns("/internal/v1/payments/**");
  }
}
