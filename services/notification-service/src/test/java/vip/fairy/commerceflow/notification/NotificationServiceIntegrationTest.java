package vip.fairy.commerceflow.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.notification.application.CustomerNotificationCommandHandler;
import vip.fairy.commerceflow.notification.application.CustomerNotificationCommandV1;
import vip.fairy.commerceflow.notification.application.CustomerNotificationService;
import vip.fairy.commerceflow.notification.infrastructure.persistence.NotificationStoreMapper;
import vip.fairy.commerceflow.notification.infrastructure.security.InternalRequestSignature;

import java.io.InputStream;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class NotificationServiceIntegrationTest {

  @Autowired
  private CustomerNotificationCommandHandler handler;

  @Autowired
  private NotificationStoreMapper notifications;

  @Autowired
  private CustomerNotificationService customerNotifications;

  @Autowired
  private InternalRequestSignature signatures;

  @Autowired
  private ObjectMapper mapper;

  @Autowired
  private MockMvc mvc;

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
      assertThat(customerNotifications.unreadCount(4L)).isEqualTo(1);
      assertThat(customerNotifications.unreadCount(5L)).isZero();
      assertThat(customerNotifications.list(4L, 20)).singleElement()
        .satisfies(notification -> {
          assertThat(notification.getEventId()).isEqualTo(command.eventId());
          assertThat(notification.getStatus()).isEqualTo("UNREAD");
        });

      customerNotifications.markRead(5L, command.eventId());
      assertThat(customerNotifications.unreadCount(4L)).isEqualTo(1);
      customerNotifications.markRead(4L, command.eventId());
      Instant firstReadAt = customerNotifications.list(4L, 20).getFirst().getReadAt();
      customerNotifications.markRead(4L, command.eventId());
      assertThat(customerNotifications.list(4L, 20).getFirst().getReadAt())
        .isEqualTo(firstReadAt);
    }
  }

  @Test
  void internalApiRejectsUnsignedRequestsAndAcceptsCommerceSignature() throws Exception {
    String target = "/internal/v1/customers/4/notifications?limit=20";
    mvc.perform(get(target)).andExpect(status().isUnauthorized());

    String timestamp = Long.toString(Instant.now().getEpochSecond());
    String requestId = UUID.randomUUID().toString();
    mvc.perform(get(target)
        .header(InternalRequestSignature.SERVICE_HEADER, "commerce-service")
        .header(InternalRequestSignature.TIMESTAMP_HEADER, timestamp)
        .header(InternalRequestSignature.REQUEST_ID_HEADER, requestId)
        .header(InternalRequestSignature.SIGNATURE_HEADER,
          signatures.sign("GET", target, timestamp, requestId)))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$").isArray());
  }
}
