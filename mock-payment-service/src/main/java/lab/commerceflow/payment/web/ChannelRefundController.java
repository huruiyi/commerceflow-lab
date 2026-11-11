package lab.commerceflow.payment.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lab.commerceflow.payment.security.SignedRequestContext;
import lab.commerceflow.payment.service.ChannelRefundService;
import lab.commerceflow.payment.web.dto.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/channel/refunds")
public class ChannelRefundController {
  private final ChannelRefundService refunds; public ChannelRefundController(ChannelRefundService refunds){this.refunds=refunds;}
  @PostMapping ChannelRefundResponse create(HttpServletRequest servletRequest,@Valid @RequestBody CreateChannelRefundRequest request){return refunds.create(signed(servletRequest),request);}
  @GetMapping ChannelRefundResponse query(HttpServletRequest servletRequest,@RequestParam String merchantRefundNo){return refunds.query(signed(servletRequest),merchantRefundNo);}
  @PostMapping("/{merchantRefundNo}/notify") ChannelRefundResponse redeliver(HttpServletRequest servletRequest,@PathVariable String merchantRefundNo){return refunds.redeliverByMerchant(signed(servletRequest),merchantRefundNo);}
  private SignedRequestContext signed(HttpServletRequest request){return(SignedRequestContext)request.getAttribute(SignedRequestContext.ATTRIBUTE);}
}
