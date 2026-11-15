package vip.fairy.commerceflow.notification.api;

import vip.fairy.commerceflow.notification.application.CustomerNotificationService;
import vip.fairy.commerceflow.notification.domain.CustomerNotificationView;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/customers/{customerId}/notifications")
public class CustomerNotificationController {

  private final CustomerNotificationService notifications;

  public CustomerNotificationController(CustomerNotificationService notifications) {
    this.notifications = notifications;
  }

  @GetMapping
  public List<CustomerNotificationView> list(@PathVariable Long customerId,
                                             @RequestParam(defaultValue = "20") int limit) {
    return notifications.list(customerId, limit);
  }

  @GetMapping("/unread-count")
  public Map<String, Integer> unreadCount(@PathVariable Long customerId) {
    return Map.of("count", notifications.unreadCount(customerId));
  }

  @PatchMapping("/{eventId}/read")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void markRead(@PathVariable Long customerId, @PathVariable String eventId) {
    notifications.markRead(customerId, eventId);
  }

  @PatchMapping("/read-all")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void markAllRead(@PathVariable Long customerId) {
    notifications.markAllRead(customerId);
  }
}
