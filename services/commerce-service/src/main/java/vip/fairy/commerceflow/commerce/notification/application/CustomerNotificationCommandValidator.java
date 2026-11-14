package vip.fairy.commerceflow.commerce.notification.application;

public final class CustomerNotificationCommandValidator {

  private CustomerNotificationCommandValidator() {
  }

  public static void validate(CustomerNotificationCommandV1 command) {
    if (command == null || command.schemaVersion() != CustomerNotificationCommandV1.VERSION
        || !CustomerNotificationCommandV1.TYPE.equals(command.eventType())) {
      throw new IllegalArgumentException("Unsupported customer notification command contract");
    }
    require(command.eventId(), "eventId");
    require(command.notificationType(), "notificationType");
    require(command.title(), "title");
    require(command.message(), "message");
    require(command.correlationId(), "correlationId");
    require(command.causationId(), "causationId");
    require(command.traceparent(), "traceparent");
    if (!CustomerNotificationCommandV1.TRACEPARENT.matcher(command.traceparent()).matches()) {
      throw new IllegalArgumentException("Customer notification command has an invalid traceparent");
    }
    if (command.occurredAt() == null || command.customerId() == null || command.orderId() == null) {
      throw new IllegalArgumentException("Customer notification command is missing required facts");
    }
  }

  private static void require(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Customer notification command is missing " + field);
    }
  }
}
