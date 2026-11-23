package vip.fairy.commerceflow.commerce.notification.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vip.fairy.commerceflow.commerce.notification.application.NotificationRecoveryService;
import vip.fairy.commerceflow.commerce.notification.application.NotificationRecoveryService.Report;
import vip.fairy.commerceflow.commerce.shared.application.CurrentActorIdResolver;

@RestController
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping("/api/admin/notification-deliveries")
public class NotificationRecoveryController {

  private final NotificationRecoveryService recovery;
  private final CurrentActorIdResolver actors;

  NotificationRecoveryController(NotificationRecoveryService recovery, CurrentActorIdResolver actors) {
    this.recovery = recovery;
    this.actors = actors;
  }

  @GetMapping("/{eventId}")
  Report get(@PathVariable @Size(max = 100) String eventId) {
    return recovery.get(eventId);
  }

  @PostMapping("/{eventId}/retries")
  Report retry(@PathVariable @Size(max = 100) String eventId, @Valid @RequestBody Retry input, Authentication user) {
    return recovery.retry(eventId, input.requestId().toString(), actors.requireId(user), input.reason().strip());
  }

  record Retry(@NotNull UUID requestId, @NotBlank @Size(max = 300) String reason) {
  }
}
