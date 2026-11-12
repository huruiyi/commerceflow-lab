package vip.fairy.commerceflow.commerce.payment.application;

import java.time.Duration;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class RedisIdempotencyService {
  private final StringRedisTemplate redis;

  public RedisIdempotencyService(StringRedisTemplate redis) {
    this.redis = redis;
  }

  public void rememberPayment(String key, String paymentNumber) {
    try {
      redis.opsForValue().set("commerceflow:idempotency:payment:" + key, paymentNumber, Duration.ofMinutes(30));
    } catch (Exception ignored) {
    }
  }
}
