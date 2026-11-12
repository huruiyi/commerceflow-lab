package vip.fairy.commerceflow.simulator.payment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import vip.fairy.commerceflow.simulator.payment.domain.PaymentAdminUser;
import vip.fairy.commerceflow.simulator.payment.mapper.PaymentAdminUserMapper;
import vip.fairy.commerceflow.simulator.payment.security.PaymentAdminUserDetailsService;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LoginProtectionService {
  private final PaymentAdminUserMapper users;
  private final StringRedisTemplate redis;
  private final int maxFailures;
  private final Duration lockDuration;
  private final Duration rateWindow;
  private final int rateAttempts;

  public LoginProtectionService(PaymentAdminUserMapper users, StringRedisTemplate redis,
                                @Value("${payment-platform.login.max-failures}") int maxFailures,
                                @Value("${payment-platform.login.lock-duration}") Duration lockDuration,
                                @Value("${payment-platform.login.rate-limit-window}") Duration rateWindow,
                                @Value("${payment-platform.login.rate-limit-attempts}") int rateAttempts) {
    this.users = users;
    this.redis = redis;
    this.maxFailures = maxFailures;
    this.lockDuration = lockDuration;
    this.rateWindow = rateWindow;
    this.rateAttempts = rateAttempts;
  }

  public void checkRateLimit(String username, String ip) {
    String key = "payment-platform:login-rate:" + ip + ":" + PaymentAdminUserDetailsService.normalize(username);
    Long count = redis.opsForValue().increment(key);
    if (count != null && count == 1) {
      redis.expire(key, rateWindow.toMillis(), TimeUnit.MILLISECONDS);
    }
    if (count != null && count > rateAttempts) {
      throw new LoginRateLimitException("登录尝试过于频繁，请稍后再试");
    }
  }

  @Transactional
  public void recordFailure(String username) {
    PaymentAdminUser user = find(username);
    if (user == null) return;
    int failures = user.getLoginFailureCount() + 1;
    user.setLoginFailureCount(failures);
    if (failures >= maxFailures) {
      user.setAccountLocked(true);
      user.setLockedUntil(LocalDateTime.now().plus(lockDuration));
    }
    user.setUpdatedAt(LocalDateTime.now());
    users.updateById(user);
  }

  @Transactional
  public void recordSuccess(String username) {
    PaymentAdminUser user = find(username);
    if (user == null) return;
    user.setLoginFailureCount(0);
    user.setAccountLocked(false);
    user.setLockedUntil(null);
    user.setLastLoginAt(LocalDateTime.now());
    user.setUpdatedAt(LocalDateTime.now());
    users.updateById(user);
  }

  private PaymentAdminUser find(String username) {
    return users.selectOne(new LambdaQueryWrapper<PaymentAdminUser>()
      .eq(PaymentAdminUser::getUsername, PaymentAdminUserDetailsService.normalize(username)));
  }
}
