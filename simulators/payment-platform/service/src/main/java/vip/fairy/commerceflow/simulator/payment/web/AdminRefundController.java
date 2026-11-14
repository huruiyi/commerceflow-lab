package vip.fairy.commerceflow.simulator.payment.web;

import vip.fairy.commerceflow.simulator.payment.security.PaymentAdminPrincipal;
import vip.fairy.commerceflow.simulator.payment.service.ChannelRefundService;
import vip.fairy.commerceflow.simulator.payment.web.dto.ChannelRefundResponse;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/admin/refunds")
public class AdminRefundController {

  private final ChannelRefundService refunds;

  public AdminRefundController(ChannelRefundService refunds) {
    this.refunds = refunds;
  }

  @GetMapping
  @PreAuthorize("hasAuthority('REFUND_READ')")
  List<ChannelRefundResponse> list(@AuthenticationPrincipal PaymentAdminPrincipal actor) {
    return refunds.listFor(actor);
  }

  @PostMapping("/{channelRefundNo}/notify")
  @PreAuthorize("hasAuthority('REFUND_OPERATE')")
  ChannelRefundResponse redeliver(@PathVariable String channelRefundNo,
                                  @AuthenticationPrincipal PaymentAdminPrincipal actor,
                                  HttpServletRequest request) {
    return refunds.redeliver(channelRefundNo, actor, request);
  }
}
