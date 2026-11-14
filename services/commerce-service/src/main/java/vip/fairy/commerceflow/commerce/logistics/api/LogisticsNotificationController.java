package vip.fairy.commerceflow.commerce.logistics.api;

import vip.fairy.commerceflow.commerce.logistics.application.LogisticsNotificationService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/logistics-notifications")
public class LogisticsNotificationController {

  private final LogisticsNotificationService service;

  public LogisticsNotificationController(LogisticsNotificationService service) {
    this.service = service;
  }

  @PostMapping("/callback")
  LogisticsNotificationService.Result callback(
    @RequestBody String raw,
    @RequestHeader("X-Callback-Signature") String signature
  ) {
    return service.accept(raw, signature);
  }
}
