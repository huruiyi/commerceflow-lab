package lab.commerceflow.storefront.service;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Locale;

@Service
public class LoginRateLimitService {
  private static final int MAX_FAILURES = 5;
  private static final Duration WINDOW = Duration.ofMinutes(5);
  private final StringRedisTemplate redis;

  public LoginRateLimitService(StringRedisTemplate redis) {
    this.redis = redis;
  }

  public void check(String email) {
    try {
      String value = redis.opsForValue().get(key(email));
      if (value != null && Integer.parseInt(value) >= MAX_FAILURES) {
        throw new LoginRateLimitException();
      }
    } catch (DataAccessException ignored) {
      // MySQL remains usable during a transient Redis outage; monitoring exposes Redis health.
    }
  }

  public void failed(String email) {
    try {
      String key = key(email);
      Long attempts = redis.opsForValue().increment(key);
      if (attempts != null && attempts == 1) {
        redis.expire(key, WINDOW);
      }
    } catch (DataAccessException ignored) {
      // Authentication still fails closed even if the optional throttle store is unavailable.
    }
  }

  public void succeeded(String email) {
    try {
      redis.delete(key(email));
    } catch (DataAccessException ignored) {
      // A stale throttle key expires automatically.
    }
  }

  private String key(String email) {
    return "commerceflow:login-failures:" + email.trim().toLowerCase(Locale.ROOT);
  }
}
