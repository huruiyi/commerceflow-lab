package vip.fairy.commerceflow.simulator.payment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import vip.fairy.commerceflow.simulator.payment.domain.ChannelTransaction;
import vip.fairy.commerceflow.simulator.payment.domain.PaymentMerchant;
import vip.fairy.commerceflow.simulator.payment.mapper.ChannelTransactionMapper;
import vip.fairy.commerceflow.simulator.payment.security.PaymentAdminPrincipal;
import vip.fairy.commerceflow.simulator.payment.security.SignedRequestContext;
import vip.fairy.commerceflow.simulator.payment.web.dto.ChannelTransactionResponse;
import vip.fairy.commerceflow.simulator.payment.web.dto.CreateChannelTransactionRequest;
import jakarta.servlet.http.HttpServletRequest;

import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.security.SecureRandom;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ChannelTransactionService {

  private static final SecureRandom RANDOM = new SecureRandom();
  private final ChannelTransactionMapper transactions;
  private final PaymentAdminRoleService roles;
  private final MerchantService merchants;
  private final AuditService audit;
  private final Map<String, ChannelAdapter> adapters;
  private final String checkoutBaseUrl;
  private final PaymentCallbackClient callbacks;

  public ChannelTransactionService(ChannelTransactionMapper transactions, PaymentAdminRoleService roles,
                                   MerchantService merchants, AuditService audit, List<ChannelAdapter> adapters,
                                   @Value("${payment-platform.checkout-base-url}") String checkoutBaseUrl,
                                   PaymentCallbackClient callbacks) {
    this.transactions = transactions;
    this.roles = roles;
    this.merchants = merchants;
    this.audit = audit;
    this.adapters = adapters.stream().collect(Collectors.toMap(ChannelAdapter::channel, Function.identity()));
    this.checkoutBaseUrl = checkoutBaseUrl;
    this.callbacks = callbacks;
  }

  @Transactional
  public ChannelTransactionResponse create(SignedRequestContext signed,
                                           CreateChannelTransactionRequest request) {
    PaymentMerchant merchant = signed.merchant();
    if ("CREATE_FAILURE".equals(request.faultScenario())) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "教学故障：渠道创建交易失败");
    }
    ChannelTransaction existing = findRequest(merchant.getId(), request.merchantRequestNo());
    if (existing != null) return replay(existing, request);
    if (!adapters.containsKey(request.channel())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的支付渠道");
    }
    ChannelTransaction value = new ChannelTransaction();
    value.setChannelTransactionNo("CP" + UUID.randomUUID().toString().replace("-", "").toUpperCase());
    value.setMerchantId(merchant.getId());
    value.setMerchantRequestNo(request.merchantRequestNo());
    value.setChannel(request.channel());
    value.setSubject(request.subject());
    value.setAmount(request.amount());
    value.setCurrency(request.currency());
    value.setStatus("PENDING");
    value.setFaultScenario(request.faultScenario() == null ? "NONE" : request.faultScenario());
    value.setCheckoutToken(randomToken());
    value.setExpiresAt(LocalDateTime.now().plusMinutes(15));
    value.setCreatedAt(LocalDateTime.now());
    value.setUpdatedAt(LocalDateTime.now());
    try {
      if (transactions.insertForRequest(value) == 1) return response(value);
    } catch (DuplicateKeyException exception) {
      ChannelTransaction concurrent = findRequest(merchant.getId(), request.merchantRequestNo());
      if (concurrent != null) return replay(concurrent, request);
      throw exception;
    }
    return replay(require(findRequest(merchant.getId(), request.merchantRequestNo())), request);
  }

  public ChannelTransactionResponse query(SignedRequestContext signed, String merchantRequestNo) {
    PaymentMerchant merchant = signed.merchant();
    return response(require(findRequest(merchant.getId(), merchantRequestNo)));
  }

  private ChannelTransaction findRequest(long merchantId, String merchantRequestNo) {
    return transactions.selectOne(new LambdaQueryWrapper<ChannelTransaction>()
      .eq(ChannelTransaction::getMerchantId, merchantId)
      .eq(ChannelTransaction::getMerchantRequestNo, merchantRequestNo));
  }

  @Transactional
  public ChannelTransactionResponse closeByMerchant(SignedRequestContext signed, String transactionNo) {
    PaymentMerchant merchant = signed.merchant();
    ChannelTransaction value = transactions.selectOne(new LambdaQueryWrapper<ChannelTransaction>()
      .eq(ChannelTransaction::getChannelTransactionNo, transactionNo)
      .eq(ChannelTransaction::getMerchantId, merchant.getId()));
    return close(require(value));
  }

  public List<ChannelTransactionResponse> listFor(PaymentAdminPrincipal actor) {
    LambdaQueryWrapper<ChannelTransaction> query = new LambdaQueryWrapper<ChannelTransaction>()
      .orderByDesc(ChannelTransaction::getId);
    boolean admin = roles.isPlatformAdmin(actor);
    if (!admin) {
      List<Long> merchantIds = roles.merchantIds(actor, "TRANSACTION_READ");
      if (merchantIds.isEmpty()) return List.of();
      query.in(ChannelTransaction::getMerchantId, merchantIds);
    }
    Set<Long> writable = admin ? Set.of() : Set.copyOf(roles.merchantIds(actor, "TRANSACTION_CLOSE"));
    return transactions.selectList(query).stream()
      .map(value -> response(value, admin || writable.contains(value.getMerchantId()))).toList();
  }

  @Transactional
  public ChannelTransactionResponse closeByAdmin(String transactionNo, PaymentAdminPrincipal actor,
                                                 HttpServletRequest request) {
    ChannelTransaction value = require(transactions.selectOne(new LambdaQueryWrapper<ChannelTransaction>()
      .eq(ChannelTransaction::getChannelTransactionNo, transactionNo)));
    roles.requireMerchant(actor, value.getMerchantId(), "TRANSACTION_CLOSE");
    ChannelTransactionResponse response = close(value);
    audit.record(actor, "CLOSE_CHANNEL_TRANSACTION", "CHANNEL_TRANSACTION", transactionNo,
      "SUCCESS", "后台关闭待支付交易", request);
    return response;
  }

  public ChannelTransactionResponse checkout(String token) {
    return response(require(transactions.selectOne(new LambdaQueryWrapper<ChannelTransaction>()
      .eq(ChannelTransaction::getCheckoutToken, token))));
  }

  @Transactional
  public ChannelTransactionResponse simulatePay(String token, String outcome) {
    ChannelTransaction value = require(transactions.selectOne(new LambdaQueryWrapper<ChannelTransaction>()
      .eq(ChannelTransaction::getCheckoutToken, token)));
    if (!"PENDING".equals(value.getStatus())) return response(value);
    if (value.getExpiresAt().isBefore(LocalDateTime.now())) {
      value.setStatus("EXPIRED");
    } else {
      switch (outcome.toUpperCase()) {
        case "SUCCESS" -> {
          value.setStatus("SUCCESS");
          value.setPaidAt(LocalDateTime.now());
        }
        case "FAILED" -> value.setStatus("FAILED");
        case "INSUFFICIENT_FUNDS" -> {
          value.setStatus("FAILED");
          value.setFaultScenario("INSUFFICIENT_FUNDS");
        }
        case "CANCELLED", "CLOSED" -> {
          value.setStatus("CLOSED");
          value.setClosedAt(LocalDateTime.now());
        }
        case "TIMEOUT", "EXPIRED" -> value.setStatus("EXPIRED");
        default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的收银台结果");
      }
    }
    value.setUpdatedAt(LocalDateTime.now());
    storeOutcome(value);
    callbacks.send(value);
    return response(value);
  }

  private ChannelTransactionResponse close(ChannelTransaction value) {
    if ("SUCCESS".equals(value.getStatus())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "成功交易不能关闭");
    }
    if ("PENDING".equals(value.getStatus())) {
      value.setStatus("CLOSED");
      value.setClosedAt(LocalDateTime.now());
      value.setUpdatedAt(LocalDateTime.now());
      storeOutcome(value);
    }
    return response(value);
  }

  private void storeOutcome(ChannelTransaction value) {
    transactions.update(null, new LambdaUpdateWrapper<ChannelTransaction>()
      .eq(ChannelTransaction::getId, value.getId())
      .set(ChannelTransaction::getStatus, value.getStatus())
      .set(ChannelTransaction::getFaultScenario, value.getFaultScenario())
      .set(ChannelTransaction::getPaidAt, value.getPaidAt())
      .set(ChannelTransaction::getClosedAt, value.getClosedAt())
      .set(ChannelTransaction::getUpdatedAt, value.getUpdatedAt()));
  }

  private ChannelTransaction require(ChannelTransaction value) {
    if (value == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "渠道交易不存在");
    return value;
  }

  private ChannelTransactionResponse replay(ChannelTransaction existing,
                                            CreateChannelTransactionRequest request) {
    String requestedFault = request.faultScenario() == null ? "NONE" : request.faultScenario();
    boolean same = existing.getChannel().equals(request.channel())
      && existing.getSubject().equals(request.subject())
      && existing.getAmount().compareTo(request.amount()) == 0
      && existing.getCurrency().equals(request.currency())
      && existing.getFaultScenario().equals(requestedFault);
    if (!same) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "商户请求号已被不同参数使用");
    }
    return response(existing);
  }

  private ChannelTransactionResponse response(ChannelTransaction value) {
    return response(value, null);
  }

  private ChannelTransactionResponse response(ChannelTransaction value, Boolean canClose) {
    ChannelAdapter.CheckoutAction action = adapters.get(value.getChannel())
      .checkoutAction(value, checkoutBaseUrl);
    return new ChannelTransactionResponse(value.getChannelTransactionNo(), value.getMerchantRequestNo(),
      value.getChannel(), value.getSubject(), value.getAmount(), value.getCurrency(), value.getStatus(),
      value.getFaultScenario(), action.type(), action.checkoutUrl(), action.qrPayload(), value.getExpiresAt(),
      value.getPaidAt(), value.getClosedAt(), value.getCreatedAt(), canClose);
  }

  private static String randomToken() {
    byte[] value = new byte[24];
    RANDOM.nextBytes(value);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
  }
}
