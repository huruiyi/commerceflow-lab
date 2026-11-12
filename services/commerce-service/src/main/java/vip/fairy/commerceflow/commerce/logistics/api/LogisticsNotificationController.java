package vip.fairy.commerceflow.commerce.logistics.api;

import vip.fairy.commerceflow.commerce.logistics.application.LogisticsNotificationService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/logistics-notifications")
public class LogisticsNotificationController {
  private final LogisticsNotificationService service;

  public LogisticsNotificationController(LogisticsNotificationService service) {
    this.service = service;
  }

  @PostMapping("/callback")
  LogisticsNotificationService.Result callback(@RequestBody String raw, @RequestHeader("X-Callback-Signature") String signature) {
    return service.accept(raw, signature);
  }
}
