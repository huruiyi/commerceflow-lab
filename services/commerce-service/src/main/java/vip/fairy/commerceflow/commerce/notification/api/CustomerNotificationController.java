package vip.fairy.commerceflow.commerce.notification.api;

import java.util.List;
import java.util.Map;

import vip.fairy.commerceflow.commerce.notification.domain.CustomerNotification;
import vip.fairy.commerceflow.commerce.identity.application.CurrentUserService;
import vip.fairy.commerceflow.commerce.notification.application.CustomerNotificationService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;

@RestController
@RequestMapping("/api/notifications")
@PreAuthorize("hasRole('CUSTOMER')")
public class CustomerNotificationController {

  private final CurrentUserService currentUsers;
  private final CustomerNotificationService notifications;

  public CustomerNotificationController(CurrentUserService currentUsers,
                                        CustomerNotificationService notifications) {
    this.currentUsers = currentUsers;
    this.notifications = notifications;
  }

  @GetMapping
  List<CustomerNotification> list(Authentication authentication) {
    return notifications.list(currentUsers.require(authentication));
  }

  @GetMapping("/unread-count")
  Map<String, Integer> unreadCount(Authentication authentication) {
    return Map.of("count", notifications.unreadCount(currentUsers.require(authentication)));
  }

  @PatchMapping("/{id}/read")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void markRead(@PathVariable Long id, Authentication authentication) {
    notifications.markRead(currentUsers.require(authentication), id);
  }

  @PatchMapping("/read-all")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void markAllRead(Authentication authentication) {
    notifications.markAllRead(currentUsers.require(authentication));
  }
}
