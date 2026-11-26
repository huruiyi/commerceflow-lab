package vip.fairy.commerceflow.simulator.payment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.servlet.http.HttpServletRequest;
import vip.fairy.commerceflow.simulator.payment.domain.ChannelRefund;
import vip.fairy.commerceflow.simulator.payment.domain.ChannelTransaction;
import vip.fairy.commerceflow.simulator.payment.domain.PaymentMerchant;
import vip.fairy.commerceflow.simulator.payment.mapper.ChannelRefundMapper;
import vip.fairy.commerceflow.simulator.payment.mapper.ChannelTransactionMapper;
import vip.fairy.commerceflow.simulator.payment.security.PaymentAdminPrincipal;
import vip.fairy.commerceflow.simulator.payment.security.SignedRequestContext;
import vip.fairy.commerceflow.simulator.payment.web.dto.ChannelRefundResponse;
import vip.fairy.commerceflow.simulator.payment.web.dto.CreateChannelRefundRequest;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ChannelRefundService {

  private final ChannelRefundMapper refunds;
  private final ChannelTransactionMapper transactions;
  private final PaymentAdminRoleService roles;
  private final RefundCallbackClient callbacks;
  private final AuditService audit;

  public ChannelRefundService(ChannelRefundMapper refunds, ChannelTransactionMapper transactions,
                              PaymentAdminRoleService roles, RefundCallbackClient callbacks,
                              AuditService audit) {
    this.refunds = refunds;
    this.transactions = transactions;
    this.roles = roles;
    this.callbacks = callbacks;
    this.audit = audit;
  }

  @Transactional
  public ChannelRefundResponse create(SignedRequestContext signed, CreateChannelRefundRequest request) {
    PaymentMerchant merchant = signed.merchant();
    ChannelTransaction payment = transactions.lockRefundPayment(merchant.getId(), request.channelTransactionNo());
    ChannelRefund existing = findRequest(merchant.getId(), request.merchantRefundNo());
    if (existing != null) {
      return replay(existing, request);
    }
    if (payment == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "原渠道交易不存在");
    }
    if (!"SUCCESS".equals(payment.getStatus())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "只有支付成功交易可以退款");
    }
    BigDecimal alreadyRefunded = refunds.selectList(new LambdaQueryWrapper<ChannelRefund>()
      .eq(ChannelRefund::getMerchantId, merchant.getId())
      .eq(ChannelRefund::getChannelTransactionNo, request.channelTransactionNo())
      .ne(ChannelRefund::getStatus, "FAILED"))
      .stream().map(ChannelRefund::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    if (request.amount().stripTrailingZeros().scale() > 2 || request.amount().signum() <= 0
        || alreadyRefunded.add(request.amount()).compareTo(payment.getAmount()) > 0
        || !payment.getCurrency().equals(request.currency())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "币种不匹配或退款超过交易剩余额度");
    }
    ChannelRefund value = new ChannelRefund();
    value.setChannelRefundNo("CR" + UUID.randomUUID().toString().replace("-", "").toUpperCase());
    value.setMerchantId(merchant.getId());
    value.setMerchantRefundNo(request.merchantRefundNo());
    value.setChannelTransactionNo(request.channelTransactionNo());
    value.setAmount(request.amount());
    value.setCurrency(request.currency());
    value.setReasonCode(request.reasonCode());
    value.setFaultScenario(request.faultScenario() == null ? "NONE" : request.faultScenario());
    boolean failed = "REFUND_FAILURE".equals(value.getFaultScenario());
    value.setStatus(failed ? "FAILED" : "SUCCEEDED");
    value.setFailureReason(failed ? "Simulated refund rejection" : "");
    value.setCreatedAt(LocalDateTime.now());
    value.setUpdatedAt(LocalDateTime.now());
    if (failed) {
      value.setFailedAt(LocalDateTime.now());
    } else {
      value.setSucceededAt(LocalDateTime.now());
    }
    try {
      if (refunds.insertForRequest(value) == 0) {
        return replay(require(findRequest(merchant.getId(), request.merchantRefundNo())), request);
      }
    } catch (DuplicateKeyException exception) {
      ChannelRefund concurrent = findRequest(merchant.getId(), request.merchantRefundNo());
      if (concurrent != null) {
        return replay(concurrent, request);
      }
      throw exception;
    }
    if (!"NOTIFY_LOST".equals(value.getFaultScenario())) {
      try {
        callbacks.send(value, payment.getChannel());
      } catch (RuntimeException ignored) {
        // 渠道退款已经落库，通知由商城对账任务补发，不能回滚资金结果。
      }
    }
    return response(value, payment.getChannel());
  }

  public ChannelRefundResponse query(SignedRequestContext signed, String merchantRefundNo) {
    ChannelRefund value = require(findRequest(signed.merchant().getId(), merchantRefundNo));
    return response(value, channel(value));
  }

  public ChannelRefundResponse redeliverByMerchant(SignedRequestContext signed, String merchantRefundNo) {
    ChannelRefund value = require(findRequest(signed.merchant().getId(), merchantRefundNo));
    String channel = channel(value);
    callbacks.send(value, channel);
    return response(value, channel);
  }

  public List<ChannelRefundResponse> listFor(PaymentAdminPrincipal actor) {
    LambdaQueryWrapper<ChannelRefund> query = new LambdaQueryWrapper<ChannelRefund>()
      .orderByDesc(ChannelRefund::getId);
    boolean admin = roles.isPlatformAdmin(actor);
    if (!admin) {
      List<Long> ids = roles.merchantIds(actor, "REFUND_READ");
      if (ids.isEmpty()) {
        return List.of();
      }
      query.in(ChannelRefund::getMerchantId, ids);
    }
    Set<Long> writable = admin ? Set.of() : Set.copyOf(roles.merchantIds(actor, "REFUND_OPERATE"));
    return refunds.selectList(query).stream()
      .map(value -> response(value, channel(value), admin || writable.contains(value.getMerchantId()))).toList();
  }

  public ChannelRefundResponse redeliver(String channelRefundNo, PaymentAdminPrincipal actor,
                                         HttpServletRequest request) {
    ChannelRefund value = require(refunds.selectOne(new LambdaQueryWrapper<ChannelRefund>()
      .eq(ChannelRefund::getChannelRefundNo, channelRefundNo)));
    roles.requireMerchant(actor, value.getMerchantId(), "REFUND_OPERATE");
    String channel = channel(value);
    callbacks.send(value, channel);
    audit.record(actor, "REDELIVER_REFUND_NOTIFICATION", "CHANNEL_REFUND", channelRefundNo,
      "SUCCESS", "重发退款结果通知，不重复执行资金退款", request);
    return response(value, channel);
  }

  private String channel(ChannelRefund value) {
    ChannelTransaction transaction = transactions.selectOne(new LambdaQueryWrapper<ChannelTransaction>()
      .eq(ChannelTransaction::getChannelTransactionNo, value.getChannelTransactionNo()));
    return transaction == null ? "" : transaction.getChannel();
  }

  private ChannelRefund findRequest(long merchantId, String merchantRefundNo) {
    return refunds.selectOne(new LambdaQueryWrapper<ChannelRefund>()
      .eq(ChannelRefund::getMerchantId, merchantId)
      .eq(ChannelRefund::getMerchantRefundNo, merchantRefundNo));
  }

  private ChannelRefund require(ChannelRefund value) {
    if (value == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "渠道退款不存在");
    }
    return value;
  }

  private ChannelRefundResponse replay(ChannelRefund existing, CreateChannelRefundRequest request) {
    boolean same = existing.getChannelTransactionNo().equals(request.channelTransactionNo())
      && existing.getAmount().compareTo(request.amount()) == 0
      && existing.getCurrency().equals(request.currency())
      && existing.getReasonCode().equals(request.reasonCode());
    if (!same) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "商户退款号已被不同参数使用");
    }
    return response(existing, channel(existing));
  }

  private ChannelRefundResponse response(ChannelRefund value, String channel) {
    return response(value, channel, null);
  }

  private ChannelRefundResponse response(ChannelRefund value, String channel, Boolean canRedeliver) {
    return new ChannelRefundResponse(value.getChannelRefundNo(), value.getMerchantRefundNo(),
      value.getChannelTransactionNo(), channel, value.getAmount(), value.getCurrency(),
      value.getReasonCode(), value.getStatus(), value.getFaultScenario(), value.getFailureReason(),
      value.getSucceededAt(), value.getFailedAt(), value.getCreatedAt(), canRedeliver);
  }
}
