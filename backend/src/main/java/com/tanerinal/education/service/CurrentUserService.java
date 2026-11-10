package com.tanerinal.education.service;

import com.tanerinal.education.domain.AppUser;
import com.tanerinal.education.repository.AppUserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

@Service
public class CurrentUserService {
  private final AppUserRepository users;

  public CurrentUserService(AppUserRepository users) {
    this.users = users;
  }

  public AppUser require(Authentication authentication) {
    if (authentication == null || !authentication.isAuthenticated()) {
      throw new IllegalStateException("Authentication is required");
    }
    return users.findByEmailIgnoreCase(authentication.getName())
        .orElseThrow(() -> new IllegalStateException("Authenticated user no longer exists"));
  }
}
