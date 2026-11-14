package vip.fairy.commerceflow.commerce.notification.api;

import vip.fairy.commerceflow.commerce.notification.application.NotificationProjectionVerificationResult;
import vip.fairy.commerceflow.commerce.notification.application.NotificationProjectionVerificationService;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/notification-projection")
@PreAuthorize("hasRole('ADMIN')")
public class NotificationProjectionAdminController {

  private final NotificationProjectionVerificationService verification;

  public NotificationProjectionAdminController(
    NotificationProjectionVerificationService verification
  ) {
    this.verification = verification;
  }

  @PostMapping("/windows")
  Map<String, Long> start() {
    return Map.of("windowId", verification.start());
  }

  @PostMapping("/check")
  NotificationProjectionVerificationResult check() {
    return verification.check();
  }

  @PostMapping("/windows/{windowId}/stop")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void stop(@PathVariable Long windowId) {
    verification.stop(windowId);
  }
}
