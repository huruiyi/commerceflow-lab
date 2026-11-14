package vip.fairy.commerceflow.simulator.payment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import vip.fairy.commerceflow.simulator.payment.domain.PaymentAdminUser;
import vip.fairy.commerceflow.simulator.payment.domain.PaymentMerchant;
import vip.fairy.commerceflow.simulator.payment.mapper.PaymentAdminUserMapper;
import vip.fairy.commerceflow.simulator.payment.mapper.PaymentMerchantMapper;
import vip.fairy.commerceflow.simulator.payment.security.PaymentAdminPrincipal;
import vip.fairy.commerceflow.simulator.payment.web.dto.CreateMerchantRequest;
import vip.fairy.commerceflow.simulator.payment.web.dto.IssuedCredentialResponse;
import vip.fairy.commerceflow.simulator.payment.web.dto.MerchantResponse;
import jakarta.servlet.http.HttpServletRequest;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MerchantService {

  private static final SecureRandom RANDOM = new SecureRandom();
  private final PaymentMerchantMapper merchants;
  private final PaymentAdminUserMapper users;
  private final CredentialCipher cipher;
  private final MfaService mfa;
  private final AuditService audit;

  public MerchantService(PaymentMerchantMapper merchants, PaymentAdminUserMapper users,
                         CredentialCipher cipher, MfaService mfa, AuditService audit) {
    this.merchants = merchants;
    this.users = users;
    this.cipher = cipher;
    this.mfa = mfa;
    this.audit = audit;
  }

  public List<MerchantResponse> list(PaymentAdminPrincipal actor, HttpServletRequest request) {
    List<MerchantResponse> result = merchants.selectList(new LambdaQueryWrapper<PaymentMerchant>()
        .orderByDesc(PaymentMerchant::getId))
      .stream()
      .map(MerchantService::response)
      .toList();
    audit.record(actor, "VIEW_MERCHANT_CREDENTIALS", "MERCHANT", null, "SUCCESS",
      "查看商户凭据元数据（敏感密钥保持遮蔽）", request);
    return result;
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
    String algorithm = request.signingAlgorithm() == null || request.signingAlgorithm().isBlank()
      ? "HMAC_SHA256" : request.signingAlgorithm().trim().toUpperCase();
    if (!List.of("HMAC_SHA256", "RSA_SHA256").contains(algorithm)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "签名算法必须是 HMAC_SHA256 或 RSA_SHA256");
    }
    if ("RSA_SHA256".equals(algorithm) && blankToNull(request.merchantPublicKey()) == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "RSA_SHA256 商户必须提供 RSA 公钥");
    }
    merchant.setSigningAlgorithm(algorithm);
    merchant.setMerchantPublicKey(blankToNull(request.merchantPublicKey()));
    merchant.setNotifyUrl(blankToNull(request.notifyUrl()));
    merchant.setEnabled(true);
    merchant.setCreatedBy(actor.getUserId());
    merchant.setUpdatedBy(actor.getUserId());
    merchant.setCreatedAt(LocalDateTime.now());
    merchant.setUpdatedAt(LocalDateTime.now());
    merchants.insert(merchant);
    if (merchant.getNotifyUrl() != null) merchants.insertCallbackEndpoint(merchant.getId(), merchant.getNotifyUrl());
    users.insertMerchantAccess(actor.getUserId(), merchant.getId(), "ADMIN", actor.getUserId());
    audit.record(actor, "CREATE_MERCHANT", "MERCHANT", merchant.getId(), "SUCCESS",
      "创建模拟支付商户并签发第 1 版凭据", servletRequest);
    return new IssuedCredentialResponse(response(merchant), secret,
      "App Secret 仅显示本次，请立即复制并安全保存；之后只能轮换。教学环境之外请接入 KMS/Vault。");
  }

  @Transactional
  public IssuedCredentialResponse rotate(long merchantId, String stepUpToken, PaymentAdminPrincipal actor,
                                         HttpServletRequest servletRequest) {
    mfa.consumeStepUp(actor, stepUpToken);
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
  public MerchantResponse setEnabled(long merchantId, boolean enabled, String stepUpToken,
                                     PaymentAdminPrincipal actor, HttpServletRequest servletRequest) {
    mfa.consumeStepUp(actor, stepUpToken);
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

  public PaymentMerchant requireByAppIdForSignature(String appId) {
    try {
      return requireByAppId(appId);
    } catch (ResponseStatusException exception) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "商户身份或签名无效");
    }
  }

  public String decryptSecret(PaymentMerchant merchant) {
    return cipher.decrypt(merchant.getSecretCipher());
  }

  private PaymentMerchant requireMerchant(long id) {
    PaymentMerchant merchant = merchants.selectById(id);
    if (merchant == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "商户不存在");
    return merchant;
  }

  private static MerchantResponse response(PaymentMerchant value) {
    return new MerchantResponse(value.getId(), value.getMerchantNo(), value.getDisplayName(), value.getAppId(),
      "****" + value.getSecretLastFour(), value.getCredentialVersion(), value.getSigningAlgorithm(),
      value.getMerchantPublicKey(),
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
