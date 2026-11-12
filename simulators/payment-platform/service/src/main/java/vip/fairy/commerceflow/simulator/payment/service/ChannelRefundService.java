package vip.fairy.commerceflow.simulator.payment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import vip.fairy.commerceflow.simulator.payment.domain.*;
import vip.fairy.commerceflow.simulator.payment.mapper.*;
import vip.fairy.commerceflow.simulator.payment.security.*;
import vip.fairy.commerceflow.simulator.payment.web.dto.*;

import java.time.LocalDateTime;
import java.util.*;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ChannelRefundService {
  private final ChannelRefundMapper refunds;
  private final ChannelTransactionMapper transactions;
  private final PaymentAdminUserMapper users;
  private final RefundCallbackClient callbacks;
  private final AuditService audit;

  public ChannelRefundService(ChannelRefundMapper refunds, ChannelTransactionMapper transactions, PaymentAdminUserMapper users, RefundCallbackClient callbacks, AuditService audit) {
    this.refunds = refunds;
    this.transactions = transactions;
    this.users = users;
    this.callbacks = callbacks;
    this.audit = audit;
  }

  @Transactional
  public ChannelRefundResponse create(SignedRequestContext signed, CreateChannelRefundRequest request) {
    PaymentMerchant merchant = signed.merchant();
    ChannelRefund existing = refunds.selectOne(new LambdaQueryWrapper<ChannelRefund>().eq(ChannelRefund::getMerchantId, merchant.getId()).eq(ChannelRefund::getMerchantRefundNo, request.merchantRefundNo()));
    if (existing != null) return replay(existing, request);
    ChannelTransaction payment = transactions.selectOne(new LambdaQueryWrapper<ChannelTransaction>().eq(ChannelTransaction::getMerchantId, merchant.getId()).eq(ChannelTransaction::getChannelTransactionNo, request.channelTransactionNo()));
    if (payment == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "原渠道交易不存在");
    if (!"SUCCESS".equals(payment.getStatus()))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "只有支付成功交易可以退款");
    if (payment.getAmount().compareTo(request.amount()) != 0 || !payment.getCurrency().equals(request.currency()))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "首版只支持原币种全额退款");
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
    if (failed) value.setFailedAt(LocalDateTime.now());
    else value.setSucceededAt(LocalDateTime.now());
    try {
      refunds.insert(value);
    } catch (DuplicateKeyException exception) {
      ChannelRefund concurrent = refunds.selectOne(new LambdaQueryWrapper<ChannelRefund>().eq(ChannelRefund::getMerchantId, merchant.getId()).eq(ChannelRefund::getMerchantRefundNo, request.merchantRefundNo()));
      if (concurrent != null) return replay(concurrent, request);
      throw exception;
    }
    if (!"NOTIFY_LOST".equals(value.getFaultScenario())) {
      try {
        callbacks.send(value, payment.getChannel());
      } catch (RuntimeException ignored) {/* 渠道退款已经落库，通知由商城对账任务补发，不能回滚资金结果。 */}
    }
    return response(value, payment.getChannel());
  }

  public ChannelRefundResponse query(SignedRequestContext signed, String merchantRefundNo) {
    ChannelRefund value = refunds.selectOne(new LambdaQueryWrapper<ChannelRefund>().eq(ChannelRefund::getMerchantId, signed.merchant().getId()).eq(ChannelRefund::getMerchantRefundNo, merchantRefundNo));
    return response(require(value), channel(value));
  }

  public ChannelRefundResponse redeliverByMerchant(SignedRequestContext signed, String merchantRefundNo) {
    ChannelRefund value = require(refunds.selectOne(new LambdaQueryWrapper<ChannelRefund>().eq(ChannelRefund::getMerchantId, signed.merchant().getId()).eq(ChannelRefund::getMerchantRefundNo, merchantRefundNo)));
    String channel = channel(value);
    callbacks.send(value, channel);
    return response(value, channel);
  }

  public List<ChannelRefundResponse> listFor(PaymentAdminPrincipal actor) {
    LambdaQueryWrapper<ChannelRefund> query = new LambdaQueryWrapper<ChannelRefund>().orderByDesc(ChannelRefund::getId);
    boolean admin = actor.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_PAYMENT_ADMIN"));
    if (!admin) {
      List<Long> ids = users.selectMerchantIds(actor.getUserId());
      if (ids.isEmpty()) return List.of();
      query.in(ChannelRefund::getMerchantId, ids);
    }
    return refunds.selectList(query).stream().map(value -> response(value, channel(value))).toList();
  }

  public ChannelRefundResponse redeliver(String channelRefundNo, PaymentAdminPrincipal actor, jakarta.servlet.http.HttpServletRequest request) {
    ChannelRefund value = require(refunds.selectOne(new LambdaQueryWrapper<ChannelRefund>().eq(ChannelRefund::getChannelRefundNo, channelRefundNo)));
    boolean admin = actor.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_PAYMENT_ADMIN"));
    if (!admin && !users.selectMerchantIds(actor.getUserId()).contains(value.getMerchantId()))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "没有该商户的退款权限");
    String channel = channel(value);
    callbacks.send(value, channel);
    audit.record(actor, "REDELIVER_REFUND_NOTIFICATION", "CHANNEL_REFUND", channelRefundNo, "SUCCESS", "重发退款结果通知，不重复执行资金退款", request);
    return response(value, channel);
  }

  private String channel(ChannelRefund value) {
    ChannelTransaction transaction = transactions.selectOne(new LambdaQueryWrapper<ChannelTransaction>().eq(ChannelTransaction::getChannelTransactionNo, value.getChannelTransactionNo()));
    return transaction == null ? "" : transaction.getChannel();
  }

  private ChannelRefund require(ChannelRefund value) {
    if (value == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "渠道退款不存在");
    return value;
  }

  private ChannelRefundResponse replay(ChannelRefund existing, CreateChannelRefundRequest request) {
    boolean same = existing.getChannelTransactionNo().equals(request.channelTransactionNo()) && existing.getAmount().compareTo(request.amount()) == 0 && existing.getCurrency().equals(request.currency()) && existing.getReasonCode().equals(request.reasonCode());
    if (!same) throw new ResponseStatusException(HttpStatus.CONFLICT, "商户退款号已被不同参数使用");
    return response(existing, channel(existing));
  }

  private ChannelRefundResponse response(ChannelRefund value, String channel) {
    return new ChannelRefundResponse(value.getChannelRefundNo(), value.getMerchantRefundNo(), value.getChannelTransactionNo(), channel, value.getAmount(), value.getCurrency(), value.getReasonCode(), value.getStatus(), value.getFaultScenario(), value.getFailureReason(), value.getSucceededAt(), value.getFailedAt(), value.getCreatedAt());
  }
}
