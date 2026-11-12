package vip.fairy.commerceflow.simulator.payment.security;

import vip.fairy.commerceflow.simulator.payment.domain.PaymentMerchant;
import vip.fairy.commerceflow.simulator.payment.domain.SignedRequestRecord;
import vip.fairy.commerceflow.simulator.payment.mapper.SignedRequestRecordMapper;
import vip.fairy.commerceflow.simulator.payment.service.MerchantService;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RequestSignatureService {
  private final MerchantService merchants;
  private final SignedRequestRecordMapper records;
  private final StringRedisTemplate redis;
  private final long allowedSkewSeconds;
  private final int requestsPerMinute;

  public RequestSignatureService(MerchantService merchants, SignedRequestRecordMapper records,
                                 StringRedisTemplate redis,
                                 @Value("${payment-platform.signature.allowed-skew:5m}") Duration allowedSkew,
                                 @Value("${payment-platform.signature.requests-per-minute:120}") int requestsPerMinute) {
    this.merchants = merchants;
    this.records = records;
    this.redis = redis;
    this.allowedSkewSeconds = allowedSkew.toSeconds();
    this.requestsPerMinute = requestsPerMinute;
  }

  public SignedRequestContext verify(String method, String target, byte[] body, Headers headers) {
    requireHeader(headers.appId(), "X-App-Id");
    requireHeader(headers.algorithm(), "X-Signature-Algorithm");
    requireHeader(headers.timestamp(), "X-Timestamp");
    requireHeader(headers.nonce(), "X-Nonce");
    requireHeader(headers.requestId(), "X-Request-Id");
    requireHeader(headers.bodySha256(), "X-Body-SHA256");
    requireHeader(headers.signature(), "X-Signature");
    if (!headers.nonce().matches("[A-Za-z0-9_-]{16,100}") ||
      !headers.requestId().matches("[A-Za-z0-9_-]{8,80}")) {
      throw unauthorized("Nonce 或请求号格式不正确");
    }
    long timestamp;
    try {
      timestamp = Long.parseLong(headers.timestamp());
    } catch (NumberFormatException exception) {
      throw unauthorized("时间戳格式不正确");
    }
    if (Math.abs(Instant.now().getEpochSecond() - timestamp) > allowedSkewSeconds) {
      throw unauthorized("请求时间戳已过期或超出允许的时钟偏差");
    }
    String actualHash = sha256Hex(body);
    if (!constantEquals(actualHash, headers.bodySha256().toLowerCase())) {
      throw unauthorized("请求体摘要不匹配，内容可能被篡改");
    }
    PaymentMerchant merchant = merchants.requireByAppIdForSignature(headers.appId());
    if (!merchant.getSigningAlgorithm().equals(headers.algorithm())) {
      throw unauthorized("签名算法与商户配置不匹配");
    }
    String canonical = canonical(method, target, headers.timestamp(), headers.nonce(),
      headers.requestId(), actualHash);
    byte[] supplied;
    try {
      supplied = Base64.getDecoder().decode(headers.signature());
    } catch (IllegalArgumentException exception) {
      throw unauthorized("签名不是有效的 Base64");
    }
    boolean verified = switch (headers.algorithm()) {
      case "HMAC_SHA256" -> constantEquals(hmac(merchants.decryptSecret(merchant), canonical), supplied);
      case "RSA_SHA256" -> verifyRsa(merchant.getMerchantPublicKey(), canonical, supplied);
      default -> throw unauthorized("不支持的签名算法");
    };
    if (!verified) throw unauthorized("请求签名验证失败");

    enforceRateLimit(merchant.getId());
    String nonceKey = "payment-platform:nonce:" + merchant.getId() + ":" + headers.nonce();
    Boolean fresh = redis.opsForValue().setIfAbsent(nonceKey, headers.requestId(),
      Duration.ofSeconds(allowedSkewSeconds * 2));
    if (!Boolean.TRUE.equals(fresh)) throw conflict("Nonce 已使用，请勿重放请求");
    try {
      SignedRequestRecord record = new SignedRequestRecord();
      record.setMerchantId(merchant.getId());
      record.setRequestId(headers.requestId());
      record.setNonce(headers.nonce());
      record.setRequestTimestamp(timestamp);
      record.setBodySha256(actualHash);
      record.setSigningAlgorithm(headers.algorithm());
      record.setRequestPath(target);
      record.setCreatedAt(LocalDateTime.now());
      records.insert(record);
    } catch (DuplicateKeyException exception) {
      throw conflict("请求号或 Nonce 已使用，请勿重放请求");
    }
    return new SignedRequestContext(merchant, headers.requestId());
  }

  private void enforceRateLimit(long merchantId) {
    String key = "payment-platform:signature-rate:" + merchantId + ":" +
      (Instant.now().getEpochSecond() / 60);
    Long count = redis.opsForValue().increment(key);
    if (count != null && count == 1) redis.expire(key, Duration.ofMinutes(2));
    if (count == null || count > requestsPerMinute) {
      throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "商户签名请求频率过高");
    }
  }

  public static String canonical(String method, String target, String timestamp, String nonce,
                                 String requestId, String bodyHash) {
    return String.join("\n", method.toUpperCase(), target, timestamp, nonce, requestId, bodyHash);
  }

  public static String sha256Hex(byte[] value) {
    try {
      return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static byte[] hmac(String secret, String canonical) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
    } catch (Exception exception) {
      throw new IllegalStateException("无法计算 HMAC", exception);
    }
  }

  private static boolean verifyRsa(String pem, String canonical, byte[] supplied) {
    if (pem == null || pem.isBlank()) throw unauthorized("商户未配置 RSA 公钥");
    try {
      String clean = pem.replace("-----BEGIN PUBLIC KEY-----", "")
        .replace("-----END PUBLIC KEY-----", "").replaceAll("\\s", "");
      PublicKey key = KeyFactory.getInstance("RSA").generatePublic(
        new X509EncodedKeySpec(Base64.getDecoder().decode(clean)));
      Signature signature = Signature.getInstance("SHA256withRSA");
      signature.initVerify(key);
      signature.update(canonical.getBytes(StandardCharsets.UTF_8));
      return signature.verify(supplied);
    } catch (ResponseStatusException exception) {
      throw exception;
    } catch (Exception exception) {
      throw unauthorized("商户 RSA 公钥或签名无效");
    }
  }

  private static boolean constantEquals(String a, String b) {
    return constantEquals(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
  }

  private static boolean constantEquals(byte[] a, byte[] b) {
    return MessageDigest.isEqual(a, b);
  }

  private static void requireHeader(String value, String name) {
    if (value == null || value.isBlank()) throw unauthorized("缺少签名头 " + name);
  }

  private static ResponseStatusException unauthorized(String message) {
    return new ResponseStatusException(HttpStatus.UNAUTHORIZED, message);
  }

  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }

  public record Headers(String appId, String algorithm, String timestamp, String nonce,
                        String requestId, String bodySha256, String signature) {
  }
}
