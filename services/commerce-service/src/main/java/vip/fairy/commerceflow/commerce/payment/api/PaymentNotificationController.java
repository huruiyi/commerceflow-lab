package vip.fairy.commerceflow.commerce.payment.api;

import vip.fairy.commerceflow.commerce.payment.application.PaymentNotificationService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payment-notifications")
public class PaymentNotificationController {

  private final PaymentNotificationService service;

  public PaymentNotificationController(PaymentNotificationService s) {
    service = s;
  }

  @PostMapping("/callback")
  public PaymentNotificationService.Result callback(
    @RequestBody String raw,
    @RequestHeader("X-Callback-Signature") String sig
  ) {
    return service.accept(raw, sig);
  }
}
