package vip.fairy.commerceflow.simulator.payment.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import vip.fairy.commerceflow.simulator.payment.domain.ChannelPayout;
import vip.fairy.commerceflow.simulator.payment.security.SignedRequestContext;
import vip.fairy.commerceflow.simulator.payment.service.ChannelPayoutDelivery;
import vip.fairy.commerceflow.simulator.payment.service.ChannelPayoutService;

@RestController
public class ChannelPayoutController {

  private final ChannelPayoutService payouts;
  private final ChannelPayoutDelivery delivery;

  public ChannelPayoutController(ChannelPayoutService payouts, ChannelPayoutDelivery delivery) {
    this.payouts = payouts;
    this.delivery = delivery;
  }

  @PostMapping("/api/channel/payouts")
  ChannelPayout create(HttpServletRequest request, @Valid @RequestBody Create input) {
    authorize(request);
    return payouts.create(input.attemptId().toString(), input.statementId().toString(),
      input.beneficiaryId(), input.amount(), input.simulateFailure());
  }

  @GetMapping("/api/channel/payouts/{id}")
  ChannelPayout status(HttpServletRequest request, @PathVariable UUID id) {
    authorize(request);
    return payouts.get(id.toString());
  }

  @PostMapping("/api/channel/payouts/{id}/notify")
  ChannelPayout notifyAgain(HttpServletRequest request, @PathVariable UUID id) {
    authorize(request);
    var value = payouts.get(id.toString());
    if (!delivery.deliver(value)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Callback still pending");
    return value;
  }

  @GetMapping("/api/admin/payouts")
  @PreAuthorize("hasAuthority('REFUND_READ')")
  List<ChannelPayout> recent() {
    return payouts.recent();
  }

  private void authorize(HttpServletRequest request) {
    var signed = (SignedRequestContext) request.getAttribute(SignedRequestContext.ATTRIBUTE);
    if (signed == null || !"commerceflow-payout".equals(signed.merchant().getAppId())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Payout application is not allowed");
    }
  }

  record Create(@NotNull UUID attemptId, @NotNull UUID statementId, @NotNull @Positive Long beneficiaryId,
                @NotNull @DecimalMin("0.01") @Digits(integer = 14, fraction = 2) BigDecimal amount,
                boolean simulateFailure) {
  }
}
