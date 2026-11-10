package com.tanerinal.payment.security;

import java.util.Collection;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.CredentialsContainer;

public final class PaymentAdminPrincipal implements UserDetails, CredentialsContainer {
  private final Long userId;
  private final String username;
  private String password;
  private final String displayName;
  private final String email;
  private final boolean enabled;
  private final boolean accountNonLocked;
  private final boolean passwordChangeRequired;
  private final boolean mfaRequired;
  private final boolean mfaEnabled;
  private final Collection<? extends GrantedAuthority> authorities;

  public PaymentAdminPrincipal(Long userId, String username, String password, String displayName,
      String email, boolean enabled, boolean accountNonLocked, boolean passwordChangeRequired,
      boolean mfaRequired, boolean mfaEnabled, Collection<? extends GrantedAuthority> authorities) {
    this.userId = userId;
    this.username = username;
    this.password = password;
    this.displayName = displayName;
    this.email = email;
    this.enabled = enabled;
    this.accountNonLocked = accountNonLocked;
    this.passwordChangeRequired = passwordChangeRequired;
    this.mfaRequired = mfaRequired;
    this.mfaEnabled = mfaEnabled;
    this.authorities = authorities;
  }

  public Long getUserId() { return userId; }
  @Override public String getUsername() { return username; }
  @Override public String getPassword() { return password; }
  public String getDisplayName() { return displayName; }
  public String getEmail() { return email; }
  @Override public boolean isEnabled() { return enabled; }
  @Override public boolean isAccountNonLocked() { return accountNonLocked; }
  @Override public boolean isAccountNonExpired() { return true; }
  @Override public boolean isCredentialsNonExpired() { return true; }
  public boolean isPasswordChangeRequired() { return passwordChangeRequired; }
  public boolean isMfaRequired() { return mfaRequired; }
  public boolean isMfaEnabled() { return mfaEnabled; }
  @Override public Collection<? extends GrantedAuthority> getAuthorities() { return authorities; }
  @Override public void eraseCredentials() { password = null; }
}
