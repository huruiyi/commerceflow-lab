package vip.fairy.commerceflow.notification.domain;

public record NotificationShadowStatus(String mode, String consumerName, int notificationCount,
                                       int inboxCount) {
}
