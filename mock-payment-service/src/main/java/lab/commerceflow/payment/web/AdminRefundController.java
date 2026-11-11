package lab.commerceflow.payment.web;

import lab.commerceflow.payment.security.PaymentAdminPrincipal;
import lab.commerceflow.payment.service.ChannelRefundService;
import lab.commerceflow.payment.web.dto.ChannelRefundResponse;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;

@RestController @RequestMapping("/api/admin/refunds")
public class AdminRefundController {
  private final ChannelRefundService refunds; public AdminRefundController(ChannelRefundService refunds){this.refunds=refunds;}
  @GetMapping @PreAuthorize("hasAuthority('REFUND_READ')") List<ChannelRefundResponse> list(@AuthenticationPrincipal PaymentAdminPrincipal actor){return refunds.listFor(actor);}
  @PostMapping("/{channelRefundNo}/notify") @PreAuthorize("hasAuthority('REFUND_OPERATE')") ChannelRefundResponse redeliver(@PathVariable String channelRefundNo,@AuthenticationPrincipal PaymentAdminPrincipal actor,HttpServletRequest request){return refunds.redeliver(channelRefundNo,actor,request);}
}
