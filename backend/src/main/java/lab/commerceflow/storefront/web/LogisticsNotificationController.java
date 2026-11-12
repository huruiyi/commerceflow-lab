package lab.commerceflow.storefront.web;

import lab.commerceflow.storefront.service.LogisticsNotificationService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/logistics-notifications")
public class LogisticsNotificationController {
  private final LogisticsNotificationService service;public LogisticsNotificationController(LogisticsNotificationService service){this.service=service;}
  @PostMapping("/callback") LogisticsNotificationService.Result callback(@RequestBody String raw,@RequestHeader("X-Callback-Signature")String signature){return service.accept(raw,signature);}
}
