package vip.fairy.commerceflow.notification;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.notification.application.CustomerNotificationCommandHandler;
import vip.fairy.commerceflow.notification.application.CustomerNotificationCommandV1;
import vip.fairy.commerceflow.notification.infrastructure.persistence.NotificationStoreMapper;

import java.io.InputStream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class NotificationServiceIntegrationTest {

  @Autowired
  private CustomerNotificationCommandHandler handler;

  @Autowired
  private NotificationStoreMapper notifications;

  @Autowired
  private ObjectMapper mapper;

  @Test
  void consumesTheFrozenContractIdempotently() throws Exception {
    try (InputStream fixture = getClass().getResourceAsStream(
      "/contracts/customer-notification-command-v1.json")) {
      CustomerNotificationCommandV1 command = mapper.readValue(fixture,
        CustomerNotificationCommandV1.class);

      assertThat(handler.handle(command)).isTrue();
      assertThat(handler.handle(command)).isFalse();
      assertThat(notifications.notificationCount()).isEqualTo(1);
      assertThat(notifications.inboxCount(CustomerNotificationCommandHandler.CONSUMER_NAME))
        .isEqualTo(1);
    }
  }
}
