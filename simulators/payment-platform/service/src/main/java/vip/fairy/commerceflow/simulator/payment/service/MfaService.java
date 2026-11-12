package vip.fairy.commerceflow.simulator.payment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import vip.fairy.commerceflow.simulator.payment.domain.PaymentAdminMfaCredential;
import vip.fairy.commerceflow.simulator.payment.domain.PaymentAdminUser;
import vip.fairy.commerceflow.simulator.payment.mapper.PaymentAdminMfaCredentialMapper;
import vip.fairy.commerceflow.simulator.payment.mapper.PaymentAdminUserMapper;
import vip.fairy.commerceflow.simulator.payment.security.PaymentAdminPrincipal;
import jakarta.servlet.http.HttpServletRequest;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MfaService {
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  private final PaymentAdminMfaCredentialMapper credentials;
  private final PaymentAdminUserMapper users;
  private final CredentialCipher cipher;
  private final PasswordEncoder passwords;
  private final StringRedisTemplate redis;
  private final AuditService audit;

  public MfaService(PaymentAdminMfaCredentialMapper credentials, PaymentAdminUserMapper users,
                    CredentialCipher cipher, PasswordEncoder passwords, StringRedisTemplate redis, AuditService audit) {
    this.credentials = credentials;
    this.users = users;
    this.cipher = cipher;
    this.passwords = passwords;
    this.redis = redis;
    this.audit = audit;
  }

  @Transactional
  public Enrollment enroll(PaymentAdminPrincipal actor, String password, HttpServletRequest request) {
    requirePassword(actor, password);
    byte[] bytes = new byte[20];
    RANDOM.nextBytes(bytes);
    String secret = base32Encode(bytes);
    PaymentAdminMfaCredential value = credential(actor.getUserId());
    if (value == null) {
      value = new PaymentAdminMfaCredential();
      value.setUserId(actor.getUserId());
      value.setMfaType("TOTP");
      value.setCreatedAt(LocalDateTime.now());
    }
    value.setSecretCipher(cipher.encrypt(secret));
    value.setEnabled(false);
    value.setVerifiedAt(null);
    if (value.getId() == null) credentials.insert(value);
    else credentials.updateById(value);
    audit.record(actor, "ENROLL_MFA", "ADMIN_USER", actor.getUserId(), "PENDING",
      "生成新的 TOTP 绑定密钥，等待验证码确认", request);
    String uri = "otpauth://totp/CommerceFlow%20Pay:" + actor.getUsername() + "?secret=" + secret +
      "&issuer=CommerceFlow%20Pay&algorithm=SHA1&digits=6&period=30";
    return new Enrollment(secret, uri);
  }

  @Transactional
  public RecoveryCodes confirm(PaymentAdminPrincipal actor, String code, HttpServletRequest request) {
    PaymentAdminMfaCredential value = credential(actor.getUserId());
    if (value == null || !verifyTotp(cipher.decrypt(value.getSecretCipher()), code)) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "TOTP 验证码不正确");
    }
    value.setEnabled(true);
    value.setVerifiedAt(LocalDateTime.now());
    credentials.updateById(value);
    PaymentAdminUser user = users.selectById(actor.getUserId());
    user.setMfaEnabled(true);
    user.setUpdatedAt(LocalDateTime.now());
    users.updateById(user);
    credentials.deleteRecoveryCodes(actor.getUserId());
    List<String> plain = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      String recovery = randomRecoveryCode();
      plain.add(recovery);
      credentials.insertRecoveryCode(actor.getUserId(), passwords.encode(recovery));
    }
    audit.record(actor, "CONFIRM_MFA", "ADMIN_USER", actor.getUserId(), "SUCCESS",
      "启用 TOTP 并重新生成 8 个一次性恢复码", request);
    return new RecoveryCodes(plain, "恢复码仅显示本次；每个恢复码只能使用一次。");
  }

  public StepUp stepUp(PaymentAdminPrincipal actor, String password, String code,
                       HttpServletRequest request) {
    requirePassword(actor, password);
    PaymentAdminUser user = users.selectById(actor.getUserId());
    String factor = "PASSWORD";
    if (user.isMfaRequired() || user.isMfaEnabled()) {
      if (!user.isMfaEnabled()) {
        throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, "请先在安全中心绑定 TOTP");
      }
      if (!verifySecondFactor(actor.getUserId(), code)) {
        audit.record(actor, "STEP_UP", "ADMIN_SESSION", null, "FAILURE", "二次认证验证码无效", request);
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "TOTP 验证码或恢复码不正确");
      }
      factor = code != null && code.contains("-") ? "RECOVERY_CODE" : "TOTP";
    }
    byte[] tokenBytes = new byte[32];
    RANDOM.nextBytes(tokenBytes);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
    redis.opsForValue().set("payment-platform:step-up:" + token, actor.getUserId().toString(), Duration.ofMinutes(5));
    audit.record(actor, "STEP_UP", "ADMIN_SESSION", null, "SUCCESS", "完成 " + factor + " 二次认证", request);
    return new StepUp(token, Instant.now().plus(Duration.ofMinutes(5)), factor);
  }

  public void consumeStepUp(PaymentAdminPrincipal actor, String token) {
    if (token == null || token.isBlank()) throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,
      "此操作需要二次认证，请先在安全中心获取 Step-up 令牌");
    String key = "payment-platform:step-up:" + token;
    String owner = redis.opsForValue().getAndDelete(key);
    if (!actor.getUserId().toString().equals(owner)) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Step-up 令牌无效、已使用或已过期");
    }
  }

  private boolean verifySecondFactor(long userId, String code) {
    if (code == null || code.isBlank()) return false;
    PaymentAdminMfaCredential value = credential(userId);
    if (value != null && value.isEnabled() && verifyTotp(cipher.decrypt(value.getSecretCipher()), code)) return true;
    for (PaymentAdminMfaCredentialMapper.RecoveryCodeRow row : credentials.selectUnusedRecoveryCodes(userId)) {
      if (passwords.matches(code.trim().toUpperCase(Locale.ROOT), row.codeHash())) {
        return credentials.consumeRecoveryCode(row.id()) == 1;
      }
    }
    return false;
  }

  private void requirePassword(PaymentAdminPrincipal actor, String password) {
    PaymentAdminUser user = users.selectById(actor.getUserId());
    if (user == null || password == null || !passwords.matches(password, user.getPasswordHash())) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "当前账户密码验证失败");
    }
  }

  private PaymentAdminMfaCredential credential(long userId) {
    return credentials.selectOne(new LambdaQueryWrapper<PaymentAdminMfaCredential>()
      .eq(PaymentAdminMfaCredential::getUserId, userId)
      .eq(PaymentAdminMfaCredential::getMfaType, "TOTP"));
  }

  static boolean verifyTotp(String secret, String code) {
    if (code == null || !code.matches("\\d{6}")) return false;
    long counter = Instant.now().getEpochSecond() / 30;
    for (long offset = -1; offset <= 1; offset++) if (totp(secret, counter + offset).equals(code)) return true;
    return false;
  }

  static String totp(String secret, long counter) {
    try {
      Mac mac = Mac.getInstance("HmacSHA1");
      mac.init(new SecretKeySpec(base32Decode(secret), "HmacSHA1"));
      byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array());
      int offset = hash[hash.length - 1] & 0xf;
      int binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16)
        | ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);
      return String.format("%06d", binary % 1_000_000);
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static String base32Encode(byte[] input) {
    StringBuilder output = new StringBuilder();
    int buffer = 0, bits = 0;
    for (byte value : input) {
      buffer = (buffer << 8) | (value & 0xff);
      bits += 8;
      while (bits >= 5) {
        output.append(BASE32.charAt((buffer >> (bits -= 5)) & 31));
      }
    }
    if (bits > 0) output.append(BASE32.charAt((buffer << (5 - bits)) & 31));
    return output.toString();
  }

  private static byte[] base32Decode(String input) {
    java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
    int buffer = 0, bits = 0;
    for (char c : input.toUpperCase(Locale.ROOT).toCharArray()) {
      int value = BASE32.indexOf(c);
      if (value < 0) continue;
      buffer = (buffer << 5) | value;
      bits += 5;
      if (bits >= 8) {
        output.write((buffer >> (bits -= 8)) & 0xff);
      }
    }
    return output.toByteArray();
  }

  private static String randomRecoveryCode() {
    byte[] bytes = new byte[9];
    RANDOM.nextBytes(bytes);
    String value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).toUpperCase(Locale.ROOT);
    return value.substring(0, 6) + "-" + value.substring(6, 12);
  }

  public record Enrollment(String secret, String otpauthUri) {
  }

  public record RecoveryCodes(List<String> codes, String notice) {
  }

  public record StepUp(String token, Instant expiresAt, String factor) {
  }
}
