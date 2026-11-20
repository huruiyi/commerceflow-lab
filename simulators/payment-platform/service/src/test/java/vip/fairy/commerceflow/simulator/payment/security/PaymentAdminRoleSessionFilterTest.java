package vip.fairy.commerceflow.simulator.payment.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import vip.fairy.commerceflow.simulator.payment.mapper.PaymentAdminRoleMapper;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class PaymentAdminRoleSessionFilterTest {

  @AfterEach
  void clearAuthentication() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void invalidatesSessionWhenAnAdminRoleWasRemoved() throws Exception {
    var users = mock(PaymentAdminRoleMapper.class);
    when(users.selectEffectivePermissions(7L)).thenReturn(List.of("ROLE_PAYMENT_ADMIN", "AUDIT_READ"));
    var request = new MockHttpServletRequest();
    var session = (MockHttpSession) request.getSession();
    var principal = authenticate();

    new PaymentAdminRoleSessionFilter(users).doFilter(request, new MockHttpServletResponse(),
      (req, res) -> assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull());

    assertThat(session.isInvalid()).isTrue();
    assertThat(principal.getAuthorities()).hasSize(2);
  }

  @Test
  void keepsSessionWhenRolesStillMatch() throws Exception {
    var users = mock(PaymentAdminRoleMapper.class);
    when(users.selectEffectivePermissions(7L)).thenReturn(List.of("ROLE_PAYMENT_ADMIN", "ADMIN_USER_MANAGE"));
    var request = new MockHttpServletRequest();
    var session = (MockHttpSession) request.getSession();
    var principal = authenticate();

    new PaymentAdminRoleSessionFilter(users).doFilter(request, new MockHttpServletResponse(),
      (req, res) -> assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal())
        .isSameAs(principal));

    assertThat(session.isInvalid()).isFalse();
  }

  private PaymentAdminPrincipal authenticate() {
    var authorities = List.of(new SimpleGrantedAuthority("ROLE_PAYMENT_ADMIN"),
      new SimpleGrantedAuthority("ADMIN_USER_MANAGE"));
    var principal = new PaymentAdminPrincipal(7L, "fixture", null, "Fixture", "fixture@example.test",
      true, true, false, false, false, authorities);
    SecurityContextHolder.getContext().setAuthentication(
      UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of(
        new SimpleGrantedAuthority("ROLE_PAYMENT_ADMIN"), new SimpleGrantedAuthority("ADMIN_USER_MANAGE"),
        new SimpleGrantedAuthority("FACTOR_PASSWORD"))));
    return principal;
  }
}
