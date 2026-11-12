package vip.fairy.commerceflow.commerce.refund.api;

import vip.fairy.commerceflow.commerce.refund.application.RefundNotificationService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/refund-notifications")
public class RefundNotificationController {
  private final RefundNotificationService service;

  public RefundNotificationController(RefundNotificationService service) {
    this.service = service;
  }

  @PostMapping("/callback")
  public RefundNotificationService.Result callback(@RequestBody String raw, @RequestHeader("X-Callback-Signature") String signature) {
    return service.accept(raw, signature);
  }
}
