package com.tanerinal.payment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tanerinal.payment.domain.PaymentAdminUser;
import com.tanerinal.payment.domain.PaymentMerchant;
import com.tanerinal.payment.mapper.PaymentAdminUserMapper;
import com.tanerinal.payment.mapper.PaymentMerchantMapper;
import com.tanerinal.payment.security.PaymentAdminPrincipal;
import com.tanerinal.payment.web.dto.CreateMerchantRequest;
import com.tanerinal.payment.web.dto.IssuedCredentialResponse;
import com.tanerinal.payment.web.dto.MerchantResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MerchantService {
  private static final SecureRandom RANDOM = new SecureRandom();
  private final PaymentMerchantMapper merchants;
  private final PaymentAdminUserMapper users;
  private final CredentialCipher cipher;
  private final PasswordEncoder passwordEncoder;
  private final AuditService audit;

  public MerchantService(PaymentMerchantMapper merchants, PaymentAdminUserMapper users,
      CredentialCipher cipher, PasswordEncoder passwordEncoder, AuditService audit) {
    this.merchants = merchants;
    this.users = users;
    this.cipher = cipher;
    this.passwordEncoder = passwordEncoder;
    this.audit = audit;
  }

  public List<MerchantResponse> list() {
    return merchants.selectList(new LambdaQueryWrapper<PaymentMerchant>().orderByDesc(PaymentMerchant::getId))
        .stream().map(MerchantService::response).toList();
  }

  @Transactional
  public IssuedCredentialResponse create(CreateMerchantRequest request, PaymentAdminPrincipal actor,
      HttpServletRequest servletRequest) {
    String secret = randomSecret(32);
    PaymentMerchant merchant = new PaymentMerchant();
    merchant.setMerchantNo("EDU" + String.format("%012d", RANDOM.nextLong(1_000_000_000_000L)));
    merchant.setDisplayName(request.displayName().trim());
    merchant.setAppId("edu_" + randomSecret(12));
    merchant.setSecretCipher(cipher.encrypt(secret));
    merchant.setSecretLastFour(secret.substring(secret.length() - 4));
    merchant.setCredentialVersion(1);
    merchant.setMerchantPublicKey(blankToNull(request.merchantPublicKey()));
    merchant.setNotifyUrl(blankToNull(request.notifyUrl()));
    merchant.setEnabled(true);
    merchant.setCreatedBy(actor.getUserId());
    merchant.setUpdatedBy(actor.getUserId());
    merchant.setCreatedAt(LocalDateTime.now());
    merchant.setUpdatedAt(LocalDateTime.now());
    merchants.insert(merchant);
    users.insertMerchantAccess(actor.getUserId(), merchant.getId(), "ADMIN", actor.getUserId());
    audit.record(actor, "CREATE_MERCHANT", "MERCHANT", merchant.getId(), "SUCCESS",
        "创建模拟支付商户并签发第 1 版凭据", servletRequest);
    return new IssuedCredentialResponse(response(merchant), secret,
        "App Secret 仅显示本次，请立即复制并安全保存；之后只能轮换。教学环境之外请接入 KMS/Vault。");
  }

  @Transactional
  public IssuedCredentialResponse rotate(long merchantId, String password, PaymentAdminPrincipal actor,
      HttpServletRequest servletRequest) {
    reauthenticate(actor, password);
    PaymentMerchant merchant = requireMerchant(merchantId);
    String secret = randomSecret(32);
    merchant.setSecretCipher(cipher.encrypt(secret));
    merchant.setSecretLastFour(secret.substring(secret.length() - 4));
    merchant.setCredentialVersion(merchant.getCredentialVersion() + 1);
    merchant.setUpdatedBy(actor.getUserId());
    merchant.setUpdatedAt(LocalDateTime.now());
    merchants.updateById(merchant);
    audit.record(actor, "ROTATE_MERCHANT_CREDENTIAL", "MERCHANT", merchantId, "SUCCESS",
        "轮换到凭据版本 " + merchant.getCredentialVersion(), servletRequest);
    return new IssuedCredentialResponse(response(merchant), secret, "旧 App Secret 已立即失效，新 Secret 仅显示本次。");
  }

  @Transactional
  public MerchantResponse setEnabled(long merchantId, boolean enabled, String password,
      PaymentAdminPrincipal actor, HttpServletRequest servletRequest) {
    reauthenticate(actor, password);
    PaymentMerchant merchant = requireMerchant(merchantId);
    merchant.setEnabled(enabled);
    merchant.setUpdatedBy(actor.getUserId());
    merchant.setUpdatedAt(LocalDateTime.now());
    merchants.updateById(merchant);
    audit.record(actor, enabled ? "ENABLE_MERCHANT" : "DISABLE_MERCHANT", "MERCHANT", merchantId,
        "SUCCESS", enabled ? "启用商户" : "禁用商户", servletRequest);
    return response(merchant);
  }

  public PaymentMerchant requireByAppId(String appId) {
    PaymentMerchant merchant = merchants.selectOne(new LambdaQueryWrapper<PaymentMerchant>()
        .eq(PaymentMerchant::getAppId, appId));
    if (merchant == null || !merchant.isEnabled()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "商户不存在或已禁用");
    }
    return merchant;
  }

  public PaymentMerchant authenticate(String appId, String suppliedSecret) {
    PaymentMerchant merchant = requireByAppId(appId);
    String expected = cipher.decrypt(merchant.getSecretCipher());
    if (suppliedSecret == null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
        suppliedSecret.getBytes(StandardCharsets.UTF_8))) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "App ID 或 App Secret 无效");
    }
    return merchant;
  }

  private PaymentMerchant requireMerchant(long id) {
    PaymentMerchant merchant = merchants.selectById(id);
    if (merchant == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "商户不存在");
    return merchant;
  }

  private void reauthenticate(PaymentAdminPrincipal actor, String password) {
    PaymentAdminUser user = users.selectById(actor.getUserId());
    if (user == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "当前账户密码验证失败");
    }
  }

  private static MerchantResponse response(PaymentMerchant value) {
    return new MerchantResponse(value.getId(), value.getMerchantNo(), value.getDisplayName(), value.getAppId(),
        "****" + value.getSecretLastFour(), value.getCredentialVersion(), value.getMerchantPublicKey(),
        value.getNotifyUrl(), value.isEnabled(), value.getCreatedBy(), value.getCreatedAt());
  }

  private static String randomSecret(int bytes) {
    byte[] value = new byte[bytes];
    RANDOM.nextBytes(value);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
