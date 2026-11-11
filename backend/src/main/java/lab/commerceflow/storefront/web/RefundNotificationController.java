package lab.commerceflow.storefront.web;

import lab.commerceflow.storefront.service.RefundNotificationService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/refund-notifications")
public class RefundNotificationController {
  private final RefundNotificationService service;
  public RefundNotificationController(RefundNotificationService service){this.service=service;}
  @PostMapping("/callback") public RefundNotificationService.Result callback(@RequestBody String raw,@RequestHeader("X-Callback-Signature")String signature){return service.accept(raw,signature);}
}
