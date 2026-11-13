package vip.fairy.commerceflow.simulator.payment.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import vip.fairy.commerceflow.simulator.payment.security.SignedRequestContext;
import vip.fairy.commerceflow.simulator.payment.service.ChannelRefundService;
import vip.fairy.commerceflow.simulator.payment.web.dto.ChannelRefundResponse;
import vip.fairy.commerceflow.simulator.payment.web.dto.CreateChannelRefundRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/channel/refunds")
public class ChannelRefundController {
  private final ChannelRefundService refunds;

  public ChannelRefundController(ChannelRefundService refunds) {
    this.refunds = refunds;
  }

  @PostMapping
  ChannelRefundResponse create(HttpServletRequest servletRequest,
                               @Valid @RequestBody CreateChannelRefundRequest request) {
    return refunds.create(signed(servletRequest), request);
  }

  @GetMapping
  ChannelRefundResponse query(HttpServletRequest servletRequest, @RequestParam String merchantRefundNo) {
    return refunds.query(signed(servletRequest), merchantRefundNo);
  }

  @PostMapping("/{merchantRefundNo}/notify")
  ChannelRefundResponse redeliver(HttpServletRequest servletRequest, @PathVariable String merchantRefundNo) {
    return refunds.redeliverByMerchant(signed(servletRequest), merchantRefundNo);
  }

  private SignedRequestContext signed(HttpServletRequest request) {
    return (SignedRequestContext) request.getAttribute(SignedRequestContext.ATTRIBUTE);
  }
}
