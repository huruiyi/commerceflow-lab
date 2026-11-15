package vip.fairy.commerceflow.commerce.shared.application;

import org.springframework.security.core.Authentication;

public interface CurrentActorIdResolver {

  Long requireId(Authentication authentication);
}
