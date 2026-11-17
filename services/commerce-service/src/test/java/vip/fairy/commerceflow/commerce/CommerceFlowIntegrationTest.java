package vip.fairy.commerceflow.commerce;

import vip.fairy.commerceflow.commerce.scheduling.application.ScheduledTaskExecution;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;

import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import vip.fairy.commerceflow.commerce.scheduling.application.ScheduledTaskExecutor;
import vip.fairy.commerceflow.commerce.scheduling.application.ScheduledTaskHandler;
import vip.fairy.commerceflow.commerce.scheduling.application.ScheduledTaskService;
import vip.fairy.commerceflow.commerce.scheduling.application.ScheduledTaskTypes;
import vip.fairy.commerceflow.commerce.scheduling.application.TaskTriggerSource;
import vip.fairy.commerceflow.commerce.identity.infrastructure.AppUserMapper;
import vip.fairy.commerceflow.commerce.ordering.application.OrderCancellationService;
import vip.fairy.commerceflow.commerce.receipt.application.OrderReceiptLifecycleService;
import vip.fairy.commerceflow.commerce.ordering.application.OrderCancellationProjection;
import vip.fairy.commerceflow.commerce.ordering.application.OrderProcessManagerService;
import vip.fairy.commerceflow.commerce.ordering.application.contract.v1.OrderCommandV1;
import vip.fairy.commerceflow.commerce.ordering.application.contract.v1.OrderCommandTypeV1;
import vip.fairy.commerceflow.commerce.ordering.application.contract.v1.OrderResultOutcomeV1;
import vip.fairy.commerceflow.commerce.ordering.application.contract.v1.OrderResultTypeV1;
import vip.fairy.commerceflow.commerce.ordering.application.contract.v1.OrderResultV1;
import vip.fairy.commerceflow.commerce.logistics.application.LogisticsNotificationService;
import vip.fairy.commerceflow.commerce.logistics.application.LogisticsGatewayClient;
import vip.fairy.commerceflow.commerce.payment.application.PaymentGatewayClient;
import vip.fairy.commerceflow.commerce.payment.application.PaymentNotificationService;
import vip.fairy.commerceflow.commerce.notification.application.CustomerNotificationCommandRouter;
import vip.fairy.commerceflow.commerce.notification.application.CustomerNotificationCommandV1;
import vip.fairy.commerceflow.commerce.notification.infrastructure.NotificationDeliveryProperties;
import vip.fairy.commerceflow.commerce.notification.infrastructure.NotificationOutboxDispatcher;
import vip.fairy.commerceflow.commerce.notification.domain.CustomerNotification;
import vip.fairy.commerceflow.commerce.notification.infrastructure.client.notification.NotificationServiceClient;
import vip.fairy.commerceflow.commerce.notification.infrastructure.persistence.commerce.NotificationMapper;
import vip.fairy.commerceflow.commerce.catalog.api.v1.CatalogProductSnapshotV1;
import vip.fairy.commerceflow.commerce.catalog.api.v1.CatalogProductV1;
import vip.fairy.commerceflow.commerce.catalog.infrastructure.client.CatalogServiceClient;
import vip.fairy.commerceflow.commerce.inventory.application.contract.v1.InventoryCommandTypeV1;
import vip.fairy.commerceflow.commerce.inventory.application.contract.v1.InventoryCommandV1;
import vip.fairy.commerceflow.commerce.inventory.application.contract.v1.InventoryCommandValidatorV1;
import vip.fairy.commerceflow.commerce.inventory.application.port.InventoryCommandPort;
import vip.fairy.commerceflow.commerce.inventory.application.InventoryCommandDeliveryStateMachine;
import vip.fairy.commerceflow.commerce.inventory.application.InventoryCommandRecoveryService;
import vip.fairy.commerceflow.commerce.fulfillment.application.MerchantFulfillmentService;
import vip.fairy.commerceflow.commerce.fulfillment.api.FulfillmentRequest;
import vip.fairy.commerceflow.commerce.inventory.application.contract.v1.InventoryCommandResultOutcomeV1;
import vip.fairy.commerceflow.commerce.inventory.application.contract.v1.InventoryCommandResultV1;
import vip.fairy.commerceflow.commerce.inventory.application.contract.v1.InventoryCommandSemantics;
import vip.fairy.commerceflow.commerce.inventory.domain.InventoryMovementView;
import vip.fairy.commerceflow.commerce.inventory.domain.MerchantInventoryItem;
import vip.fairy.commerceflow.commerce.inventory.infrastructure.transport.MerchantInventoryRemoteClient;
import vip.fairy.commerceflow.commerce.ordering.application.PurchaseOrderService;
import vip.fairy.commerceflow.commerce.shared.application.BusinessRuleException;
import vip.fairy.commerceflow.commerce.payment.domain.PaymentChannel;
import vip.fairy.commerceflow.commerce.payment.api.PaymentRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.session.MapSessionRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.when;

@ActiveProfiles("test")
@SpringBootTest(properties = {
  "spring.kafka.listener.auto-startup=false",
  "commerceflow.outbox.delay=3600000",
  "commerceflow.reconciliation.delay=3600000",
  "commerceflow.scheduling.database-scan.initial-delay=1h",
  "commerceflow.scheduling.lease-recovery.initial-delay=1h",
  "commerceflow.scheduling.reconciliation.initial-delay=1h",
  "commerceflow.inventory.delivery.dispatch-initial-delay=1h",
  "commerceflow.inventory.delivery.recovery-initial-delay=1h",
  "commerceflow.order-process.recovery-initial-delay=1h"
})
@AutoConfigureMockMvc
@Import(CommerceFlowIntegrationTest.TestSessions.class)
class CommerceFlowIntegrationTest {

  @TestConfiguration
  static class TestSessions {

    @Bean
    MapSessionRepository sessionRepository() {
      return new MapSessionRepository(new ConcurrentHashMap<>());
    }

    @Bean
    AtomicInteger reminderExecutions() {
      return new AtomicInteger();
    }

    @Bean
    ScheduledTaskHandler reminderTestHandler(AtomicInteger reminderExecutions) {
      return new ScheduledTaskHandler() {
        @Override
        public String taskType() {
          return ScheduledTaskTypes.REVIEW_REMINDER;
        }

        @Override
        public void execute(vip.fairy.commerceflow.commerce.scheduling.application.ScheduledTaskExecution task) {
          reminderExecutions.incrementAndGet();
        }
      };
    }
  }

  @Autowired
  MockMvc mvc;
  @Autowired
  ObjectMapper json;
  @Autowired
  JdbcTemplate jdbc;
  @Autowired
  ScheduledTaskService scheduledTasks;
  @Autowired
  ScheduledTaskExecutor scheduledTaskExecutor;
  @Autowired
  AtomicInteger reminderExecutions;
  @Autowired
  OrderReceiptLifecycleService receiptLifecycle;
  @Autowired
  LogisticsNotificationService logisticsNotifications;
  @Autowired
  OrderCancellationProjection orderCancellationProjection;
  @Autowired
  OrderProcessManagerService orderProcesses;
  @Autowired
  PaymentNotificationService paymentNotifications;
  @Autowired
  OrderCancellationService orderCancellations;
  @Autowired
  PurchaseOrderService purchaseOrders;
  @Autowired
  InventoryCommandPort inventoryCommands;
  @Autowired
  InventoryCommandValidatorV1 inventoryCommandValidator;
  @Autowired
  InventoryCommandDeliveryStateMachine inventoryDeliveryStateMachine;
  @Autowired
  InventoryCommandRecoveryService inventoryCommandRecoveries;
  @Autowired
  MerchantFulfillmentService merchantFulfillment;
  @Autowired
  CustomerNotificationCommandRouter notificationRouter;
  @Autowired
  NotificationOutboxDispatcher notificationOutbox;
  @Autowired
  NotificationDeliveryProperties notificationDelivery;
  @Autowired
  AppUserMapper users;
  @MockitoBean
  PaymentGatewayClient paymentGateway;
  @MockitoBean
  KafkaTemplate<String, String> kafka;
  @MockitoBean
  NotificationServiceClient notificationServiceClient;
  @MockitoBean
  CatalogServiceClient catalogServiceClient;
  @MockitoBean
  MerchantInventoryRemoteClient merchantInventoryRemoteClient;

  @BeforeEach
  void catalogServiceContract() {
    when(catalogServiceClient.list(nullable(String.class))).thenAnswer(invocation -> {
      String locale = invocation.getArgument(0);
      return List.of(catalogProduct(1L, locale));
    });
    when(catalogServiceClient.detail(any(Long.class), nullable(String.class))).thenAnswer(invocation ->
      catalogProduct(invocation.getArgument(0), invocation.getArgument(1)));
    when(catalogServiceClient.snapshots(any())).thenAnswer(invocation -> {
      List<Long> ids = invocation.getArgument(0);
      return ids.stream().distinct().map(id -> new CatalogProductSnapshotV1(
        1, id, 5L, "Learning Laptop", new BigDecimal("1299.00"), "thumb",
        "ACTIVE", 1L)).toList();
    });
  }

  private CatalogProductV1 catalogProduct(Long productId, String locale) {
    boolean chinese = locale != null && locale.toLowerCase().startsWith("zh");
    return new CatalogProductV1(1, productId, 5L,
      chinese ? "学习笔记本电脑" : "Learning Laptop",
      chinese ? "适合编程实验的实用笔记本电脑" : "A practical laptop for coding labs",
      chinese ? "用于 Java、Angular 和 Docker 学习的工作站。"
        : "Java, Angular and Docker learning workstation.",
      new BigDecimal("1299.00"), "image", "thumb", "ACTIVE", 1L, Instant.now());
  }

  @Test
  void customerNotificationV1FixtureRemainsCompatible() throws Exception {
    try (var fixture = getClass().getResourceAsStream(
      "/contracts/customer-notification-command-v1.json")) {
      org.junit.jupiter.api.Assertions.assertNotNull(fixture);
      CustomerNotificationCommandV1 command = json.readValue(
        fixture, CustomerNotificationCommandV1.class);
      org.junit.jupiter.api.Assertions.assertEquals(CustomerNotificationCommandV1.VERSION,
        command.schemaVersion());
      org.junit.jupiter.api.Assertions.assertEquals(CustomerNotificationCommandV1.TYPE,
        command.eventType());
      org.junit.jupiter.api.Assertions.assertEquals("contract-notification-0001", command.eventId());
      org.junit.jupiter.api.Assertions.assertEquals("order-2001", command.correlationId());
      org.junit.jupiter.api.Assertions.assertEquals("order-cancelled-0001", command.causationId());
      org.junit.jupiter.api.Assertions.assertEquals(
        "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
        command.traceparent());
    }
  }

  @Test
  void inventoryCommandV1FixturesRemainCompatible() throws Exception {
    Map<String, InventoryCommandTypeV1> fixtures = Map.of(
      "inventory-reserve-command-v1.json", InventoryCommandTypeV1.RESERVE,
      "inventory-commit-command-v1.json", InventoryCommandTypeV1.COMMIT,
      "inventory-release-command-v1.json", InventoryCommandTypeV1.RELEASE,
      "inventory-refund-command-v1.json", InventoryCommandTypeV1.REFUND
    );
    for (Map.Entry<String, InventoryCommandTypeV1> fixture : fixtures.entrySet()) {
      try (var input = getClass().getResourceAsStream(
        "/contracts/inventory/" + fixture.getKey())) {
        org.junit.jupiter.api.Assertions.assertNotNull(input);
        InventoryCommandV1 command = json.readValue(input, InventoryCommandV1.class);
        inventoryCommandValidator.validate(command);
        org.junit.jupiter.api.Assertions.assertEquals(InventoryCommandV1.VERSION,
          command.schemaVersion());
        org.junit.jupiter.api.Assertions.assertEquals(fixture.getValue(), command.commandType());
      }
    }
  }

  @Test
  void orderCommandAndResultV1FixturesRemainCompatible() throws Exception {
    try (var input = getClass().getResourceAsStream(
      "/contracts/ordering/order-ship-command-v1.json")) {
      org.junit.jupiter.api.Assertions.assertNotNull(input);
      OrderCommandV1 command = json.readValue(input, OrderCommandV1.class);
      command.validate();
      org.junit.jupiter.api.Assertions.assertEquals(OrderCommandV1.VERSION,
        command.schemaVersion());
    }
    try (var input = getClass().getResourceAsStream(
      "/contracts/ordering/order-payment-result-v1.json")) {
      org.junit.jupiter.api.Assertions.assertNotNull(input);
      OrderResultV1 result = json.readValue(input, OrderResultV1.class);
      result.validate();
      org.junit.jupiter.api.Assertions.assertEquals(OrderResultV1.VERSION,
        result.schemaVersion());
    }
  }

  @Test
  void orderProcessManagerPersistsIdempotencyUnknownResultsAndRecovery() {
    String commandId = UUID.randomUUID().toString();
    long orderId = 8_000_000L + Math.abs(UUID.randomUUID().getMostSignificantBits() % 100_000L);
    String traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
    OrderCommandV1 command = new OrderCommandV1(
      1, commandId, OrderCommandTypeV1.SHIP, Instant.now(), "fulfillment", orderId, 5L,
      "CONFIRMED", "Merchant shipped", "SF Express", "SF-" + orderId,
      "order-" + orderId, "ship-" + orderId, traceparent
    );
    Instant deadline = Instant.now().plusSeconds(300);
    var started = orderProcesses.start(command, OrderResultTypeV1.LOGISTICS,
      "logistics-service", deadline);
    org.junit.jupiter.api.Assertions.assertTrue(started.created());
    org.junit.jupiter.api.Assertions.assertFalse(orderProcesses.start(command,
      OrderResultTypeV1.LOGISTICS, "logistics-service", deadline).created());

    OrderCommandV1 conflicting = new OrderCommandV1(
      1, commandId, OrderCommandTypeV1.SHIP, command.occurredAt(), "fulfillment", orderId, 5L,
      "CONFIRMED", "Merchant shipped", "SF Express", "DIFFERENT",
      command.correlationId(), command.causationId(), traceparent
    );
    org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
      () -> orderProcesses.start(conflicting, OrderResultTypeV1.LOGISTICS,
        "logistics-service", deadline));

    String resultId = UUID.randomUUID().toString();
    OrderResultV1 result = new OrderResultV1(
      1, resultId, OrderResultTypeV1.LOGISTICS, OrderResultOutcomeV1.SUCCEEDED,
      Instant.now(), "logistics", orderId, "shipment-" + orderId, "CREATED", null,
      "Shipment accepted", command.correlationId(), commandId, traceparent
    );
    org.junit.jupiter.api.Assertions.assertEquals(OrderProcessManagerService.Acceptance.APPLIED,
      orderProcesses.accept(result));
    org.junit.jupiter.api.Assertions.assertEquals(OrderProcessManagerService.Acceptance.DUPLICATE,
      orderProcesses.accept(result));
    org.junit.jupiter.api.Assertions.assertEquals("COMPLETED", jdbc.queryForObject(
      "SELECT status FROM order_process_managers WHERE process_id=?", String.class, commandId));
    org.junit.jupiter.api.Assertions.assertEquals("ACKNOWLEDGED", jdbc.queryForObject(
      "SELECT status FROM order_command_outbox WHERE command_id=?", String.class, commandId));

    OrderResultV1 conflictingResult = new OrderResultV1(
      1, resultId, OrderResultTypeV1.LOGISTICS, OrderResultOutcomeV1.SUCCEEDED,
      result.occurredAt(), "logistics", orderId, result.externalReference(), "DELIVERED", null,
      "Different payload", result.correlationId(), result.causationId(), traceparent
    );
    org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
      () -> orderProcesses.accept(conflictingResult));

    OrderResultV1 unsolicited = new OrderResultV1(
      1, UUID.randomUUID().toString(), OrderResultTypeV1.PAYMENT,
      OrderResultOutcomeV1.SUCCEEDED, Instant.now(), "payment", orderId,
      "payment-" + orderId, "SUCCESS", null, "Unsolicited result",
      command.correlationId(), commandId, traceparent
    );
    org.junit.jupiter.api.Assertions.assertEquals(OrderProcessManagerService.Acceptance.IGNORED,
      orderProcesses.accept(unsolicited));

    String recoveryCommandId = UUID.randomUUID().toString();
    OrderCommandV1 recoveryCommand = new OrderCommandV1(
      1, recoveryCommandId, OrderCommandTypeV1.CANCEL, Instant.now(), "ordering", orderId + 1,
      2L, "PENDING_PAYMENT", "Customer cancelled", null, null,
      "order-" + (orderId + 1), "cancel-" + (orderId + 1), traceparent
    );
    orderProcesses.start(recoveryCommand, OrderResultTypeV1.PAYMENT,
      "payment-orchestration-service", Instant.now().plusSeconds(300));
    jdbc.update("UPDATE order_process_managers SET result_deadline_at=? WHERE process_id=?",
      java.sql.Timestamp.from(Instant.now().minusSeconds(1)), recoveryCommandId);
    orderProcesses.markExpiredUnknownResults();
    org.junit.jupiter.api.Assertions.assertEquals("RECONCILIATION_REQUIRED", jdbc.queryForObject(
      "SELECT status FROM order_process_managers WHERE process_id=?", String.class,
      recoveryCommandId));
    orderProcesses.recover(recoveryCommandId, "admin", "Remote result checked",
      Instant.now().plusSeconds(30));
    org.junit.jupiter.api.Assertions.assertEquals("WAITING_RESULT", jdbc.queryForObject(
      "SELECT status FROM order_process_managers WHERE process_id=?", String.class,
      recoveryCommandId));
  }

  @Test
  void inventoryCommandResultV1FixturesRemainCompatible() throws Exception {
    Map<String, InventoryCommandResultOutcomeV1> fixtures = Map.of(
      "inventory-command-result-succeeded-v1.json", InventoryCommandResultOutcomeV1.SUCCEEDED,
      "inventory-command-result-rejected-v1.json", InventoryCommandResultOutcomeV1.REJECTED
    );
    for (Map.Entry<String, InventoryCommandResultOutcomeV1> fixture : fixtures.entrySet()) {
      try (var input = getClass().getResourceAsStream(
        "/contracts/inventory/" + fixture.getKey())) {
        org.junit.jupiter.api.Assertions.assertNotNull(input);
        InventoryCommandResultV1 result = json.readValue(input, InventoryCommandResultV1.class);
        result.validate();
        org.junit.jupiter.api.Assertions.assertEquals(fixture.getValue(), result.outcome());
      }
    }
  }

  @Test
  @Transactional
  void inventoryDeliveryOutboxMakesReplayIdempotentAndRejectsPayloadReuse() {
    long orderId = 9_000_000L + Math.abs(UUID.randomUUID().getMostSignificantBits() % 100_000L);
    String commandId = "inventory-contract-" + UUID.randomUUID();
    InventoryCommandV1 command = InventoryCommandV1.reserve(
      commandId,
      orderId,
      List.of(new InventoryCommandV1.Item(1L, "Learning Laptop", 1))
    );

    inventoryCommands.handle(command);
    inventoryCommands.handle(command);

    org.junit.jupiter.api.Assertions.assertEquals("NEW",
      jdbc.queryForObject(
        "SELECT status FROM inventory_command_delivery_outbox WHERE command_id=?", String.class,
        commandId));

    InventoryCommandV1 conflicting = InventoryCommandV1.reserve(
      commandId,
      orderId,
      List.of(new InventoryCommandV1.Item(1L, "Learning Laptop", 2))
    );
    org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
      () -> inventoryCommands.handle(conflicting));
  }

  @Test
  @Transactional
  void inventoryCommandResultTimeoutRequiresReconciliationAfterAttemptLimit() throws Exception {
    String commandId = "inventory-timeout-" + UUID.randomUUID();
    Long customerId = jdbc.queryForObject(
      "SELECT id FROM app_users WHERE email='demo@example.com'", Long.class);
    String orderNumber = "INVENTORY-TIMEOUT-" + UUID.randomUUID();
    jdbc.update("INSERT INTO shop_orders(order_number,customer_id,status,inventory_status,total_amount,"
        + "currency,payment_due_at,created_at,updated_at) VALUES(?,?,'PAID','WAITING_COMMIT',1.00,"
        + "'CNY',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", orderNumber, customerId);
    Long orderId = jdbc.queryForObject(
      "SELECT id FROM shop_orders WHERE order_number=?", Long.class, orderNumber);
    InventoryCommandV1 command = InventoryCommandV1.commit(commandId, orderId, commandId);
    String payload = json.writeValueAsString(command);
    jdbc.update("INSERT INTO inventory_command_delivery_outbox(command_id,command_type,producer,"
        + "order_id,payload_fingerprint,payload,status) VALUES(?,?,?,?,?,?,?)",
      commandId, "COMMIT", "payment", orderId, InventoryCommandSemantics.fingerprint(command), payload, "NEW");

    org.junit.jupiter.api.Assertions.assertEquals(1,
      inventoryDeliveryStateMachine.markInFlight(commandId, java.time.Duration.ofSeconds(1)));
    jdbc.update("UPDATE inventory_command_delivery_outbox "
      + "SET result_deadline_at=DATEADD('SECOND',-1,CURRENT_TIMESTAMP) WHERE command_id=?",
      commandId);
    org.junit.jupiter.api.Assertions.assertEquals(1,
      inventoryDeliveryStateMachine.recoverExpired(1, java.time.Duration.ZERO));

    org.junit.jupiter.api.Assertions.assertEquals("RECONCILIATION_REQUIRED",
      jdbc.queryForObject(
        "SELECT status FROM inventory_command_delivery_outbox WHERE command_id=?", String.class,
        commandId));
    org.junit.jupiter.api.Assertions.assertEquals("RECONCILIATION_REQUIRED",
      jdbc.queryForObject("SELECT inventory_status FROM shop_orders WHERE id=?", String.class, orderId));

    org.junit.jupiter.api.Assertions.assertEquals("RETRY",
      inventoryCommandRecoveries.retry(commandId, "admin@example.com", "Verified remote result").status());
    org.junit.jupiter.api.Assertions.assertEquals(1,
      inventoryDeliveryStateMachine.markInFlight(commandId, java.time.Duration.ofSeconds(1)));
    inventoryDeliveryStateMachine.acknowledge(command, InventoryCommandResultV1.succeeded(command));
    org.junit.jupiter.api.Assertions.assertEquals("COMMITTED",
      jdbc.queryForObject("SELECT inventory_status FROM shop_orders WHERE id=?", String.class, orderId));
    org.junit.jupiter.api.Assertions.assertEquals("admin@example.com|Verified remote result",
      jdbc.queryForObject("SELECT CONCAT(recovered_by,'|',recovery_reason) "
        + "FROM inventory_command_delivery_outbox WHERE command_id=?", String.class, commandId));
  }

  @Test
  @Transactional
  void fulfillmentIsBlockedUntilInventoryCommitIsConfirmed() {
    Long customerId = jdbc.queryForObject(
      "SELECT id FROM app_users WHERE email='demo@example.com'", Long.class);
    Long merchantId = jdbc.queryForObject(
      "SELECT id FROM app_users WHERE email='merchant@example.com'", Long.class);
    String orderNumber = "INVENTORY-GATE-" + UUID.randomUUID();
    jdbc.update("INSERT INTO shop_orders(order_number,customer_id,status,inventory_status,total_amount,"
        + "currency,payment_due_at,created_at,updated_at) VALUES(?,?,'CONFIRMED','WAITING_COMMIT',1.00,"
        + "'CNY',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", orderNumber, customerId);
    Long orderId = jdbc.queryForObject(
      "SELECT id FROM shop_orders WHERE order_number=?", Long.class, orderNumber);
    jdbc.update("INSERT INTO order_items(order_id,product_id,merchant_id,product_name,thumbnail_url,"
        + "unit_price,quantity,subtotal) VALUES(?,?,?,?,?,1.00,1,1.00)",
      orderId, 1L, merchantId, "Inventory gate", "");

    org.junit.jupiter.api.Assertions.assertThrows(BusinessRuleException.class,
      () -> merchantFulfillment.ship(users.selectById(merchantId), orderId,
        new FulfillmentRequest("SF", "BLOCKED")));
  }

  @Test
  void notificationKafkaOutboxRecoversAndPropagatesTrace() throws Exception {
    org.mockito.Mockito.reset(kafka);
    jdbc.update("DELETE FROM notification_delivery_outbox");
    int originalMaxAttempts = notificationDelivery.getMaxAttempts();
    java.time.Duration originalRetryDelay = notificationDelivery.getRetryDelay();
    try {
      notificationDelivery.setMaxAttempts(3);
      notificationDelivery.setRetryDelay(java.time.Duration.ZERO);
      CustomerNotificationCommandV1 recovered = CustomerNotificationCommandV1.orderCancelled(
        "async-recovery-" + UUID.randomUUID(), 4L, 2001L, "Broker recovery", "order-2001");
      when(kafka.send(any(ProducerRecord.class)))
        .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")))
        .thenReturn(CompletableFuture.completedFuture(null));

      notificationRouter.publish(recovered);
      notificationOutbox.dispatch();
      org.junit.jupiter.api.Assertions.assertEquals("RETRY", outboxStatus(recovered.eventId()));
      notificationOutbox.dispatch();
      org.junit.jupiter.api.Assertions.assertEquals("PUBLISHED", outboxStatus(recovered.eventId()));

      org.mockito.ArgumentCaptor<ProducerRecord<String, String>> records =
        org.mockito.ArgumentCaptor.forClass(ProducerRecord.class);
      org.mockito.Mockito.verify(kafka, org.mockito.Mockito.times(2)).send(records.capture());
      ProducerRecord<String, String> published = records.getAllValues().get(1);
      org.junit.jupiter.api.Assertions.assertEquals(recovered.traceparent(),
        new String(published.headers().lastHeader("traceparent").value(), StandardCharsets.UTF_8));
      org.mockito.Mockito.reset(kafka);
      notificationDelivery.setMaxAttempts(1);
      CustomerNotificationCommandV1 dead = CustomerNotificationCommandV1.orderCancelled(
        "async-dead-" + UUID.randomUUID(), 4L, 2001L, "Dead then replay", "order-2001");
      when(kafka.send(any(ProducerRecord.class)))
        .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")))
        .thenReturn(CompletableFuture.completedFuture(null));
      notificationRouter.publish(dead);
      notificationOutbox.dispatch();
      org.junit.jupiter.api.Assertions.assertEquals("DEAD", outboxStatus(dead.eventId()));
      org.junit.jupiter.api.Assertions.assertEquals(1, notificationOutbox.replayDead(dead.eventId()));
      notificationOutbox.dispatch();
      org.junit.jupiter.api.Assertions.assertEquals("PUBLISHED", outboxStatus(dead.eventId()));

    } finally {
      notificationDelivery.setMaxAttempts(originalMaxAttempts);
      notificationDelivery.setRetryDelay(originalRetryDelay);
      org.mockito.Mockito.reset(kafka);
    }
  }

  private String outboxStatus(String eventId) {
    return jdbc.queryForObject(
      "SELECT status FROM notification_delivery_outbox WHERE event_id=?", String.class, eventId);
  }

  @Test
  void publicCatalogSupportsLocalesAndAuthenticationReturnsStableErrors() throws Exception {
    mvc.perform(get("/api/products?lang=en")).andExpect(status().isOk())
      .andExpect(jsonPath("$[0].name").value("Learning Laptop"));
    mvc.perform(get("/api/products?lang=zh-CN")).andExpect(status().isOk())
      .andExpect(jsonPath("$[0].name").value("学习笔记本电脑"));
    mvc.perform(post("/api/auth/login").contentType("application/json")
        .content("{\"email\":\"demo@example.com\",\"password\":\"demo-password\",\"remember\":false}"))
      .andExpect(status().isForbidden());
    CsrfContext csrf = csrfContext();
    mvc.perform(post("/api/auth/login").session(csrf.session()).cookie(csrf.cookie())
        .header("X-XSRF-TOKEN", csrf.token()).contentType("application/json")
        .content("{\"email\":\"demo@example.com\",\"password\":\"wrong-password\",\"remember\":false}"))
      .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH_INVALID"));
  }

  @Test
  void customerCartDeduplicationQuantityRemovalAndAuthorizationWork() throws Exception {
    CsrfContext csrf = login("demo@example.com", true);
    Long customerId = jdbc.queryForObject(
      "SELECT id FROM app_users WHERE email='demo@example.com'", Long.class);
    jdbc.update("DELETE FROM cart_items WHERE cart_id IN (SELECT id FROM carts WHERE user_id=?)", customerId);
    mvc.perform(get("/api/auth/me").session(csrf.session())).andExpect(status().isOk())
      .andExpect(jsonPath("$.role").value("CUSTOMER"));
    mvc.perform(post("/api/cart/items").session(csrf.session()).cookie(csrf.cookie())
        .header("X-XSRF-TOKEN", csrf.token()).contentType("application/json")
        .content("{\"productId\":1,\"quantity\":2,\"note\":\"first\"}"))
      .andExpect(status().isOk()).andExpect(jsonPath("$.quantity").value(2));
    MvcResult merged = mvc.perform(post("/api/cart/items").session(csrf.session()).cookie(csrf.cookie())
        .header("X-XSRF-TOKEN", csrf.token()).contentType("application/json")
        .content("{\"productId\":1,\"quantity\":3,\"note\":\"merged\"}"))
      .andExpect(status().isOk()).andExpect(jsonPath("$.quantity").value(5)).andReturn();
    long itemId = json.readTree(merged.getResponse().getContentAsString()).get("id").asLong();
    mvc.perform(get("/api/cart").session(csrf.session())).andExpect(status().isOk())
      .andExpect(jsonPath("$.length()").value(1));
    mvc.perform(put("/api/cart/items/{id}", itemId).session(csrf.session()).cookie(csrf.cookie())
        .header("X-XSRF-TOKEN", csrf.token()).contentType("application/json")
        .content("{\"quantity\":0}"))
      .andExpect(status().isBadRequest());
    mvc.perform(get("/api/merchant/orders").session(csrf.session())).andExpect(status().isForbidden());
    mvc.perform(get("/api/users").session(csrf.session())).andExpect(status().isForbidden());
    mvc.perform(delete("/api/cart/items/{id}", itemId).session(csrf.session()).cookie(csrf.cookie())
        .header("X-XSRF-TOKEN", csrf.token()))
      .andExpect(status().isNoContent());
    mvc.perform(get("/api/cart").session(csrf.session())).andExpect(status().isOk())
      .andExpect(jsonPath("$").isEmpty());
  }

  @Test
  void customerOrderProjectionKeepsProductIdentity() throws Exception {
    CsrfContext csrf = login("demo@example.com", false);
    MvcResult created = mvc.perform(post("/api/orders").session(csrf.session()).cookie(csrf.cookie())
        .header("X-XSRF-TOKEN", csrf.token()).contentType("application/json")
        .content("{\"productId\":1,\"quantity\":2,\"note\":\"projection\"}"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.productId").value(1))
      .andReturn();
    long orderId = json.readTree(created.getResponse().getContentAsString()).get("id").asLong();

    mvc.perform(put("/api/orders/{orderId}/quantity", orderId)
        .session(csrf.session()).cookie(csrf.cookie())
        .header("X-XSRF-TOKEN", csrf.token()).contentType("application/json")
        .content("{\"quantity\":3}"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.productId").value(1))
      .andExpect(jsonPath("$.quantity").value(3));
  }

  @Test
  void adminCanListUsers() throws Exception {
    CsrfContext csrf = login("admin@example.com", false);
    mvc.perform(get("/api/users").session(csrf.session())).andExpect(status().isOk())
      .andExpect(jsonPath("$[0].email").isNotEmpty());
  }

  @Test
  void genericSchedulingUsesPolicySnapshotAndAdminCanMaintainPolicies() throws Exception {
    scheduledTasks.schedule(ScheduledTaskTypes.REVIEW_REMINDER, "review-reminder-test", "ORDER", "1",
      Instant.now().minusSeconds(1), Map.of("orderId", 1));
    org.junit.jupiter.api.Assertions.assertEquals(1, scheduledTaskExecutor.executeDatabaseBatch(10));
    org.junit.jupiter.api.Assertions.assertEquals(1, reminderExecutions.get());
    org.junit.jupiter.api.Assertions.assertEquals("SUCCEEDED",
      jdbc.queryForObject("SELECT status FROM scheduled_tasks WHERE task_key='review-reminder-test'", String.class));
    Long taskId = jdbc.queryForObject(
      "SELECT id FROM scheduled_tasks WHERE task_key='review-reminder-test'", Long.class);

    CsrfContext customer = login("demo@example.com", false);
    mvc.perform(get("/api/admin/scheduling/policies").session(customer.session()))
      .andExpect(status().isForbidden());

    CsrfContext admin = login("admin@example.com", false);
    mvc.perform(get("/api/admin/scheduling/policies").session(admin.session()))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$[?(@.taskType == 'REVIEW_REMINDER')].handlerAvailable").value(true));
    mvc.perform(put("/api/admin/scheduling/policies/REVIEW_REMINDER")
        .session(admin.session()).cookie(admin.cookie()).header("X-XSRF-TOKEN", admin.token())
        .contentType("application/json")
        .content("{\"enabled\":true,\"primaryTrigger\":\"DATABASE\",\"reconciliationEnabled\":true,\"gracePeriodSeconds\":60,\"claimLeaseSeconds\":30,\"maxAttempts\":4,\"retryDelaySeconds\":5}"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.source").value("DATABASE_OVERRIDE"))
      .andExpect(jsonPath("$.maxAttempts").value(4));
    mvc.perform(get("/api/admin/scheduling/policies/REVIEW_REMINDER/events").session(admin.session()))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$[0].action").value("UPSERT"))
      .andExpect(jsonPath("$[0].operatedBy").value("admin@example.com"));
    mvc.perform(get("/api/admin/scheduling/tasks/{id}/events", taskId).session(admin.session()))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$[0].eventType").value("TASK_SCHEDULED"))
      .andExpect(jsonPath("$[2].eventType").value("TASK_SUCCEEDED"));
    mvc.perform(put("/api/admin/scheduling/policies/UNREGISTERED_TASK")
        .session(admin.session()).cookie(admin.cookie()).header("X-XSRF-TOKEN", admin.token())
        .contentType("application/json")
        .content("{\"enabled\":true,\"primaryTrigger\":\"DATABASE\",\"reconciliationEnabled\":true,\"gracePeriodSeconds\":60,\"claimLeaseSeconds\":30,\"maxAttempts\":4,\"retryDelaySeconds\":5}"))
      .andExpect(status().isConflict());
    mvc.perform(put("/api/admin/scheduling/policies/REVIEW_REMINDER")
        .session(admin.session()).cookie(admin.cookie()).header("X-XSRF-TOKEN", admin.token())
        .contentType("application/json")
        .content("{\"enabled\":true,\"primaryTrigger\":\"RABBITMQ\",\"reconciliationEnabled\":true,\"gracePeriodSeconds\":60,\"claimLeaseSeconds\":30,\"maxAttempts\":4,\"retryDelaySeconds\":5}"))
      .andExpect(status().isConflict());
    mvc.perform(put("/api/admin/scheduling/policies/REVIEW_REMINDER")
        .session(admin.session()).cookie(admin.cookie()).header("X-XSRF-TOKEN", admin.token())
        .contentType("application/json")
        .content("{\"enabled\":true,\"primaryTrigger\":\"ROCKETMQ\",\"reconciliationEnabled\":true,\"gracePeriodSeconds\":60,\"claimLeaseSeconds\":30,\"maxAttempts\":4,\"retryDelaySeconds\":5}"))
      .andExpect(status().isConflict());
    mvc.perform(delete("/api/admin/scheduling/policies/REVIEW_REMINDER")
        .session(admin.session()).cookie(admin.cookie()).header("X-XSRF-TOKEN", admin.token()))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.source").value("CONFIG_TASK_POLICY"));
  }

  @Test
  void paymentTimeoutTaskUsesTheSharedCancellationHandler() throws Exception {
    Long customerId = jdbc.queryForObject(
      "SELECT id FROM app_users WHERE email='demo@example.com'", Long.class);
    jdbc.update("INSERT INTO shop_orders(order_number,customer_id,status,total_amount,currency,payment_due_at,created_at,updated_at) VALUES('TIMEOUT-ORDER',?,'PENDING_PAYMENT',1299,'CNY',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", customerId);
    Long orderId = jdbc.queryForObject(
      "SELECT id FROM shop_orders WHERE order_number='TIMEOUT-ORDER'", Long.class);
    var task = scheduledTasks.schedule(ScheduledTaskTypes.PAYMENT_TIMEOUT, "order:" + orderId,
      "ORDER", orderId.toString(), Instant.now().minusSeconds(1), Map.of("orderId", orderId));

    CsrfContext customer = login("demo@example.com", false);
    mvc.perform(get("/api/purchase-orders/{id}", orderId).session(customer.session()))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.availableActions").isArray())
      .andExpect(jsonPath("$.availableActions[?(@ == 'PAY')]").exists())
      .andExpect(jsonPath("$.availableActions[?(@ == 'CANCEL_ORDER')]").exists());

    org.junit.jupiter.api.Assertions.assertTrue(
      scheduledTaskExecutor.execute(task.getId(), TaskTriggerSource.DATABASE_SCAN));
    org.junit.jupiter.api.Assertions.assertEquals("CANCELLED",
      jdbc.queryForObject("SELECT status FROM shop_orders WHERE id=?", String.class, orderId));
    org.junit.jupiter.api.Assertions.assertEquals(1, jdbc.queryForObject(
      "SELECT COUNT(*) FROM order_events WHERE order_id=? AND event_type='ORDER_CANCELLED' AND trigger_source='SCHEDULED_TASK' AND scheduled_task_id=?",
      Integer.class, orderId, task.getId()));
    org.junit.jupiter.api.Assertions.assertEquals(1, jdbc.queryForObject(
      "SELECT COUNT(*) FROM payment_outbox WHERE aggregate_type='ORDER' AND aggregate_id=? AND event_type='ORDER_CANCELLED'",
      Integer.class, orderId.toString()));
  }

  @Test
  void expiredPaymentAttemptAllowsNewChannelBeforeButNotAfterOrderDeadline() throws Exception {
    Long customerId = jdbc.queryForObject(
      "SELECT id FROM app_users WHERE email='demo@example.com'", Long.class);
    String suffix = UUID.randomUUID().toString().replace("-", "");
    jdbc.update("MERGE INTO payment_merchant_accounts(channel,merchant_no,enabled) KEY(channel) VALUES('WECHAT_PAY','WECHAT-DEADLINE-TEST',TRUE)");
    jdbc.update("INSERT INTO shop_orders(order_number,customer_id,status,total_amount,currency,payment_due_at,created_at,updated_at) VALUES(?,?,'PENDING_PAYMENT',88.00,'CNY',?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
      "ORDER-ATTEMPT-" + suffix, customerId, java.sql.Timestamp.from(Instant.now().plusSeconds(60)));
    Long orderId = jdbc.queryForObject(
      "SELECT id FROM shop_orders WHERE order_number=?", Long.class, "ORDER-ATTEMPT-" + suffix);
    String expiredTransaction = "TX-EXPIRED-" + suffix;
    jdbc.update("INSERT INTO payment_records(order_id,payment_number,idempotency_key,channel,currency,request_fingerprint,channel_transaction_no,amount,status,failure_reason,expires_at,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,'',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
      orderId, "PAY-EXPIRED-" + suffix.substring(0, 20), "expired-" + suffix,
      "ALIPAY", "CNY", suffix, expiredTransaction, new java.math.BigDecimal("88.00"), "PENDING");

    String raw = json.writeValueAsString(Map.of(
      "notificationId", "NOTICE-EXPIRED-" + suffix,
      "channelTransactionNo", expiredTransaction,
      "channel", "ALIPAY", "status", "EXPIRED",
      "amount", new java.math.BigDecimal("88.00"), "currency", "CNY", "failureReason", "Attempt expired"));
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec("demo-platform-callback-secret".getBytes(StandardCharsets.UTF_8),
      "HmacSHA256"));
    org.junit.jupiter.api.Assertions.assertEquals("PROCESSED", paymentNotifications.accept(raw,
      Base64.getEncoder().encodeToString(mac.doFinal(raw.getBytes(StandardCharsets.UTF_8)))).result());
    org.junit.jupiter.api.Assertions.assertEquals("PENDING_PAYMENT",
      jdbc.queryForObject("SELECT status FROM shop_orders WHERE id=?", String.class, orderId));

    org.mockito.Mockito.doReturn(new PaymentGatewayClient.Result(
        "TX-RETRY-" + suffix, "PENDING", "QR_CODE", null, "retry-qr",
        java.time.LocalDateTime.now(java.time.Clock.systemUTC()).plusMinutes(5)))
      .when(paymentGateway).create(org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.eq(PaymentChannel.WECHAT_PAY),
        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.eq("CNY"), org.mockito.ArgumentMatchers.eq("NONE"));
    var retry = purchaseOrders.pay(users.selectById(customerId), orderId,
      new PaymentRequest("retry-" + suffix, false, PaymentChannel.WECHAT_PAY));
    org.junit.jupiter.api.Assertions.assertEquals("PENDING_PAYMENT", retry.status());
    org.junit.jupiter.api.Assertions.assertEquals("PENDING", retry.payment().status());
    org.junit.jupiter.api.Assertions.assertEquals(1, jdbc.queryForObject(
      "SELECT COUNT(*) FROM payment_records WHERE order_id=? AND status='EXPIRED'", Integer.class, orderId));
    org.junit.jupiter.api.Assertions.assertEquals(1, jdbc.queryForObject(
      "SELECT COUNT(*) FROM payment_records WHERE order_id=? AND status='PENDING'", Integer.class, orderId));

    jdbc.update("INSERT INTO shop_orders(order_number,customer_id,status,total_amount,currency,payment_due_at,created_at,updated_at) VALUES(?,?,'PENDING_PAYMENT',66.00,'CNY',?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
      "ORDER-DEADLINE-" + suffix, customerId, java.sql.Timestamp.from(Instant.now().minusSeconds(1)));
    Long expiredOrderId = jdbc.queryForObject(
      "SELECT id FROM shop_orders WHERE order_number=?", Long.class, "ORDER-DEADLINE-" + suffix);
    org.junit.jupiter.api.Assertions.assertThrows(BusinessRuleException.class,
      () -> purchaseOrders.pay(users.selectById(customerId), expiredOrderId,
        new PaymentRequest("after-deadline-" + suffix, false, PaymentChannel.WECHAT_PAY)));
    org.junit.jupiter.api.Assertions.assertEquals(0, jdbc.queryForObject(
      "SELECT COUNT(*) FROM payment_records WHERE order_id=?", Integer.class, expiredOrderId));
  }

  @Test
  void illegalOrderStateTransitionsAreRejectedWithoutChangingFacts() {
    Long customerId = jdbc.queryForObject(
      "SELECT id FROM app_users WHERE email='demo@example.com'", Long.class);
    String suffix = UUID.randomUUID().toString().replace("-", "");
    String orderNumber = "ORDER-ILLEGAL-" + suffix;
    jdbc.update("INSERT INTO shop_orders(order_number,customer_id,status,total_amount,currency,payment_due_at,created_at,updated_at) VALUES(?,?,'PAID',77.00,'CNY',?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
      orderNumber, customerId, java.sql.Timestamp.from(Instant.now().plusSeconds(60)));
    Long paidOrderId = jdbc.queryForObject(
      "SELECT id FROM shop_orders WHERE order_number=?", Long.class, orderNumber);

    org.junit.jupiter.api.Assertions.assertThrows(BusinessRuleException.class,
      () -> purchaseOrders.pay(users.selectById(customerId), paidOrderId,
        new PaymentRequest("illegal-pay-" + suffix, false, PaymentChannel.ALIPAY)));
    org.junit.jupiter.api.Assertions.assertThrows(BusinessRuleException.class,
      () -> orderCancellations.cancelByCustomer(users.selectById(customerId), paidOrderId,
        "Illegal paid-order cancellation"));
    org.junit.jupiter.api.Assertions.assertThrows(BusinessRuleException.class,
      () -> receiptLifecycle.confirmReceipt(customerId, customerId, paidOrderId));
    receiptLifecycle.autoReceive(paidOrderId, null);
    receiptLifecycle.autoComplete(paidOrderId, null);

    org.junit.jupiter.api.Assertions.assertEquals("PAID",
      jdbc.queryForObject("SELECT status FROM shop_orders WHERE id=?", String.class, paidOrderId));
    org.junit.jupiter.api.Assertions.assertEquals(0, jdbc.queryForObject(
      "SELECT COUNT(*) FROM payment_records WHERE order_id=?", Integer.class, paidOrderId));
    org.junit.jupiter.api.Assertions.assertEquals(0, jdbc.queryForObject(
      "SELECT COUNT(*) FROM order_events WHERE order_id=?", Integer.class, paidOrderId));
  }

  @Test
  void checkoutAndCancellationEnqueueInventoryCommandsIdempotently() throws Exception {
    Long customerId = jdbc.queryForObject(
      "SELECT id FROM app_users WHERE email='demo@example.com'", Long.class);
    Long merchantId = jdbc.queryForObject(
      "SELECT id FROM app_users WHERE email='admin@example.com'", Long.class);
    jdbc.update("UPDATE products SET merchant_id=? WHERE id=1", merchantId);
    jdbc.update("MERGE INTO carts(user_id,created_at,updated_at) KEY(user_id) VALUES(?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", customerId);
    Long cartId = jdbc.queryForObject("SELECT id FROM carts WHERE user_id=?", Long.class, customerId);
    jdbc.update("DELETE FROM cart_items WHERE cart_id=?", cartId);
    jdbc.update("INSERT INTO cart_items(cart_id,product_id,merchant_id,product_name,thumbnail_url,unit_price,quantity,created_at,updated_at) VALUES(?,1,?,'Learning Laptop','thumb',1299,2,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", cartId, merchantId);
    jdbc.update("INSERT INTO shipping_addresses(user_id,recipient_name,phone,country_code,state_province,city,district,address_line1,address_line2,postal_code,label,is_default,created_at,updated_at) VALUES(?,'Inventory Customer','13800000000','CN','Shanghai','Shanghai','Pudong','Stock Road','','200000','Inventory',TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", customerId);
    Long addressId = jdbc.queryForObject("SELECT MAX(id) FROM shipping_addresses WHERE user_id=?", Long.class, customerId);
    jdbc.update("UPDATE carts SET selected_address_id=? WHERE id=?", addressId, cartId);
    CsrfContext customer = login("demo@example.com", false);
    MvcResult checkout = mvc.perform(post("/api/purchase-orders/checkout")
        .session(customer.session()).cookie(customer.cookie()).header("X-XSRF-TOKEN", customer.token()))
      .andExpect(status().isOk()).andReturn();
    long orderId = json.readTree(checkout.getResponse().getContentAsString()).get("id").asLong();
    org.junit.jupiter.api.Assertions.assertEquals("WAITING_RESERVATION", jdbc.queryForObject(
      "SELECT inventory_status FROM shop_orders WHERE id=?", String.class, orderId));
    org.junit.jupiter.api.Assertions.assertEquals("NEW", jdbc.queryForObject(
      "SELECT status FROM inventory_command_delivery_outbox WHERE command_id=?", String.class,
      "order:" + orderId + ":reserve"));

    mvc.perform(post("/api/purchase-orders/{id}/cancel", orderId)
        .session(customer.session()).cookie(customer.cookie()).header("X-XSRF-TOKEN", customer.token())
        .contentType("application/json").content("{\"reason\":\"Inventory test cancellation\"}"))
      .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
    Map<String, Object> outbox = jdbc.queryForMap(
      "SELECT event_id,event_type,aggregate_id,payload FROM payment_outbox WHERE aggregate_type='ORDER' AND aggregate_id=? AND event_type='ORDER_CANCELLED' ORDER BY id DESC LIMIT 1",
      Long.toString(orderId));
    Object payloadValue = outbox.get("PAYLOAD");
    String payload = payloadValue instanceof byte[] bytes
      ? new String(bytes, StandardCharsets.UTF_8) : payloadValue.toString();
    String envelope = json.writeValueAsString(Map.of(
      "eventId", outbox.get("EVENT_ID"),
      "eventType", outbox.get("EVENT_TYPE"),
      "aggregateId", outbox.get("AGGREGATE_ID"),
      "payload", json.readTree(payload)));
    orderCancellationProjection.process("order-events", envelope);
    orderCancellationProjection.process("order-events", envelope);

    org.junit.jupiter.api.Assertions.assertEquals(2, jdbc.queryForObject(
      "SELECT COUNT(*) FROM inventory_command_delivery_outbox WHERE order_id=?", Integer.class, orderId));
    org.junit.jupiter.api.Assertions.assertEquals(1, jdbc.queryForObject(
      "SELECT COUNT(*) FROM inventory_command_delivery_outbox WHERE order_id=? AND command_type='RELEASE'",
      Integer.class, orderId));
    String notificationEventId = outbox.get("EVENT_ID") + ":notification";
    when(notificationServiceClient.list(customerId)).thenReturn(List.of(
      new CustomerNotification(1L, notificationEventId, orderId, "ORDER_CANCELLED",
        "Order cancelled", "Inventory test cancellation", "UNREAD", null, Instant.now())));
    when(notificationServiceClient.unreadCount(customerId)).thenReturn(1);
    mvc.perform(get("/api/notifications").session(customer.session()))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$[0].eventId").value(notificationEventId))
      .andExpect(jsonPath("$[0].status").value("UNREAD"));
    mvc.perform(get("/api/notifications/unread-count").session(customer.session()))
      .andExpect(status().isOk()).andExpect(jsonPath("$.count").value(1));
    mvc.perform(patch("/api/notifications/{eventId}/read", notificationEventId)
        .session(customer.session()).cookie(customer.cookie()).header("X-XSRF-TOKEN", customer.token()))
      .andExpect(status().isNoContent());
    org.mockito.Mockito.verify(notificationServiceClient).markRead(customerId,
      notificationEventId);
  }

  @Test
  void lateSuccessfulPaymentAfterCancellationQueuesOneAutomaticRefund() throws Exception {
    Long customerId = jdbc.queryForObject(
      "SELECT id FROM app_users WHERE email='demo@example.com'", Long.class);
    String suffix = UUID.randomUUID().toString().replace("-", "");
    String transactionNo = "TX-LATE-" + suffix;
    String notificationId = "NOTICE-LATE-" + suffix;
    jdbc.update("INSERT INTO shop_orders(order_number,customer_id,status,total_amount,currency,payment_due_at,cancelled_at,cancel_reason,created_at,updated_at) VALUES(?,?,'CANCELLED',88.00,'CNY',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'Customer cancelled',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
      "ORDER-LATE-" + suffix, customerId);
    Long orderId = jdbc.queryForObject(
      "SELECT id FROM shop_orders WHERE order_number=?", Long.class, "ORDER-LATE-" + suffix);
    jdbc.update("INSERT INTO payment_records(order_id,payment_number,idempotency_key,channel,currency,request_fingerprint,channel_transaction_no,amount,status,failure_reason,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,'',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
      orderId, "PAY-LATE-" + suffix.substring(0, 24), "payment-late-" + suffix, "ALIPAY", "CNY",
      suffix, transactionNo, new java.math.BigDecimal("88.00"), "PENDING");

    String raw = json.writeValueAsString(Map.of(
      "notificationId", notificationId,
      "channelTransactionNo", transactionNo,
      "channel", "ALIPAY",
      "status", "SUCCESS",
      "amount", new java.math.BigDecimal("88.00"),
      "currency", "CNY",
      "failureReason", ""));
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec("demo-platform-callback-secret".getBytes(StandardCharsets.UTF_8),
      "HmacSHA256"));
    String signature = Base64.getEncoder().encodeToString(
      mac.doFinal(raw.getBytes(StandardCharsets.UTF_8)));

    org.junit.jupiter.api.Assertions.assertEquals("PROCESSED",
      paymentNotifications.accept(raw, signature).result());
    org.junit.jupiter.api.Assertions.assertEquals("ACKNOWLEDGED",
      paymentNotifications.accept(raw, signature).result());
    org.junit.jupiter.api.Assertions.assertEquals("REFUNDING",
      jdbc.queryForObject("SELECT status FROM shop_orders WHERE id=?", String.class, orderId));
    org.junit.jupiter.api.Assertions.assertEquals(1, jdbc.queryForObject(
      "SELECT COUNT(*) FROM refund_records WHERE order_id=? AND idempotency_key=? AND reason_code='CUSTOMER_CANCELLED' AND status='PROCESSING'",
      Integer.class, orderId, "late-payment:" + notificationId));
    org.junit.jupiter.api.Assertions.assertEquals(1, jdbc.queryForObject(
      "SELECT COUNT(*) FROM order_events WHERE order_id=? AND event_type='LATE_PAYMENT_AFTER_CANCELLATION'",
      Integer.class, orderId));
    org.junit.jupiter.api.Assertions.assertEquals(1, jdbc.queryForObject(
      "SELECT COUNT(*) FROM order_events WHERE order_id=? AND event_type='ORDER_AUTO_REFUND_REQUESTED'",
      Integer.class, orderId));
  }

  @Test
  void successfulPaymentAndCancellationRaceKeepsThePaymentFact() throws Exception {
    Long customerId = jdbc.queryForObject(
      "SELECT id FROM app_users WHERE email='demo@example.com'", Long.class);
    String suffix = UUID.randomUUID().toString().replace("-", "");
    String orderNumber = "ORDER-RACE-" + suffix;
    String transactionNo = "TX-RACE-" + suffix;
    jdbc.update("INSERT INTO shop_orders(order_number,customer_id,status,inventory_status,total_amount,"
        + "currency,payment_due_at,created_at,updated_at) VALUES(?,?,'PENDING_PAYMENT','RESERVED',66.00,"
        + "'CNY',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
      orderNumber, customerId);
    Long orderId = jdbc.queryForObject(
      "SELECT id FROM shop_orders WHERE order_number=?", Long.class, orderNumber);
    jdbc.update("INSERT INTO payment_records(order_id,payment_number,idempotency_key,channel,currency,request_fingerprint,channel_transaction_no,amount,status,failure_reason,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,'',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
      orderId, "PAY-RACE-" + suffix.substring(0, 23), "payment-race-" + suffix,
      "ALIPAY", "CNY", suffix, transactionNo, new java.math.BigDecimal("66.00"), "PENDING");

    CountDownLatch closeStarted = new CountDownLatch(1);
    CountDownLatch allowClose = new CountDownLatch(1);
    org.mockito.Mockito.doAnswer(invocation -> {
      closeStarted.countDown();
      await(allowClose);
      return new PaymentGatewayClient.CloseResult(transactionNo, "CLOSED");
    }).when(paymentGateway).close(org.mockito.ArgumentMatchers.any());

    ExecutorService workers = Executors.newSingleThreadExecutor();
    try {
      Future<?> cancellation = workers.submit(() -> orderCancellations.cancelByCustomer(
        users.selectById(customerId), orderId, "Concurrent cancellation"));
      org.junit.jupiter.api.Assertions.assertTrue(closeStarted.await(5, TimeUnit.SECONDS));

      String raw = json.writeValueAsString(Map.of(
        "notificationId", "NOTICE-RACE-" + suffix,
        "channelTransactionNo", transactionNo,
        "channel", "ALIPAY",
        "status", "SUCCESS",
        "amount", new java.math.BigDecimal("66.00"),
        "currency", "CNY",
        "failureReason", ""));
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec("demo-platform-callback-secret".getBytes(StandardCharsets.UTF_8),
        "HmacSHA256"));
      String signature = Base64.getEncoder().encodeToString(
        mac.doFinal(raw.getBytes(StandardCharsets.UTF_8)));
      org.junit.jupiter.api.Assertions.assertEquals("PROCESSED",
        paymentNotifications.accept(raw, signature).result());
      allowClose.countDown();
      cancellation.get(10, TimeUnit.SECONDS);
    } finally {
      allowClose.countDown();
      workers.shutdownNow();
    }

    org.junit.jupiter.api.Assertions.assertEquals("PAID",
      jdbc.queryForObject("SELECT status FROM shop_orders WHERE id=?", String.class, orderId));
    org.junit.jupiter.api.Assertions.assertEquals("SUCCESS",
      jdbc.queryForObject("SELECT status FROM payment_records WHERE order_id=?", String.class, orderId));
    org.junit.jupiter.api.Assertions.assertEquals(1, jdbc.queryForObject(
      "SELECT COUNT(*) FROM order_events WHERE order_id=? AND event_type='CANCELLATION_LOST_PAYMENT_RACE'",
      Integer.class, orderId));
    org.junit.jupiter.api.Assertions.assertEquals(0, jdbc.queryForObject(
      "SELECT COUNT(*) FROM order_events WHERE order_id=? AND event_type='ORDER_CANCELLED'",
      Integer.class, orderId));
  }

  @Test
  void merchantCanAuditAndAdjustOnlyOwnedAvailableInventory() throws Exception {
    Long merchantId = jdbc.queryForObject(
      "SELECT id FROM app_users WHERE email='merchant@example.com'", Long.class);
    MerchantInventoryItem before = new MerchantInventoryItem(
      1L, "Learning Laptop", "thumb", new BigDecimal("1299.00"), 40, 0, 0);
    MerchantInventoryItem after = new MerchantInventoryItem(
      1L, "Learning Laptop", "thumb", new BigDecimal("1299.00"), 45, 0, 0);
    InventoryMovementView movement = new InventoryMovementView(
      1L, null, 1L, "MANUAL_INCREASE", 5, 45, 0, 0,
      "Cycle count correction", Instant.now());
    when(merchantInventoryRemoteClient.list(merchantId)).thenReturn(List.of(before));
    when(merchantInventoryRemoteClient.adjust(merchantId, 1L, 5, "Cycle count correction"))
      .thenReturn(after);
    when(merchantInventoryRemoteClient.movements(merchantId, 1L))
      .thenReturn(List.of(movement));
    CsrfContext merchant = login("merchant@example.com", false);

    mvc.perform(get("/api/merchant/inventory").session(merchant.session()))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$[?(@.productId == 1)].stockAvailable").value(40));
    mvc.perform(post("/api/merchant/inventory/1/adjustments")
        .session(merchant.session()).cookie(merchant.cookie())
        .header("X-XSRF-TOKEN", merchant.token()).contentType("application/json")
        .content("{\"delta\":5,\"reason\":\"Cycle count correction\"}"))
      .andExpect(status().isOk()).andExpect(jsonPath("$.stockAvailable").value(45));
    mvc.perform(get("/api/merchant/inventory/1/movements").session(merchant.session()))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$[0].movementType").value("MANUAL_INCREASE"))
      .andExpect(jsonPath("$[0].detail").value("Cycle count correction"));

    CsrfContext customer = login("demo@example.com", false);
    mvc.perform(get("/api/merchant/inventory").session(customer.session()))
      .andExpect(status().isForbidden());
    org.mockito.Mockito.verify(merchantInventoryRemoteClient)
      .adjust(merchantId, 1L, 5, "Cycle count correction");
  }

  @Test
  void automaticReceiptAndCompletionUseSharedScheduledTaskHandlers() {
    Long customerId = jdbc.queryForObject(
      "SELECT id FROM app_users WHERE email='demo@example.com'", Long.class);
    jdbc.update("INSERT INTO shop_orders(order_number,customer_id,status,total_amount,currency,payment_due_at,logistics_delivered_at,created_at,updated_at) VALUES('AUTO-LIFECYCLE-ORDER',?,'SHIPPED',1299,'CNY',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", customerId);
    Long orderId = jdbc.queryForObject(
      "SELECT id FROM shop_orders WHERE order_number='AUTO-LIFECYCLE-ORDER'", Long.class);

    receiptLifecycle.scheduleAutoReceive(orderId, Instant.now().minusSeconds(2));
    Long receiveTaskId = jdbc.queryForObject(
      "SELECT id FROM scheduled_tasks WHERE task_type='AUTO_RECEIVE' AND task_key=?",
      Long.class, "order:" + orderId);
    org.junit.jupiter.api.Assertions.assertTrue(
      scheduledTaskExecutor.execute(receiveTaskId, TaskTriggerSource.DATABASE_SCAN));
    org.junit.jupiter.api.Assertions.assertEquals("RECEIVED",
      jdbc.queryForObject("SELECT status FROM shop_orders WHERE id=?", String.class, orderId));
    org.junit.jupiter.api.Assertions.assertNotNull(
      jdbc.queryForObject("SELECT received_at FROM shop_orders WHERE id=?", java.sql.Timestamp.class, orderId));
    org.junit.jupiter.api.Assertions.assertEquals(1, jdbc.queryForObject(
      "SELECT COUNT(*) FROM order_events WHERE order_id=? AND event_type='ORDER_AUTO_RECEIVED' AND scheduled_task_id=?",
      Integer.class, orderId, receiveTaskId));

    Long completeTaskId = jdbc.queryForObject(
      "SELECT id FROM scheduled_tasks WHERE task_type='AUTO_COMPLETE' AND task_key=?",
      Long.class, "order:" + orderId);
    jdbc.update("UPDATE scheduled_tasks SET due_at=? WHERE id=?", java.sql.Timestamp.from(Instant.now().minusSeconds(1)), completeTaskId);
    org.junit.jupiter.api.Assertions.assertTrue(
      scheduledTaskExecutor.execute(completeTaskId, TaskTriggerSource.DATABASE_SCAN));
    org.junit.jupiter.api.Assertions.assertEquals("COMPLETED",
      jdbc.queryForObject("SELECT status FROM shop_orders WHERE id=?", String.class, orderId));
    org.junit.jupiter.api.Assertions.assertNotNull(
      jdbc.queryForObject("SELECT completed_at FROM shop_orders WHERE id=?", java.sql.Timestamp.class, orderId));
    org.junit.jupiter.api.Assertions.assertEquals(1, jdbc.queryForObject(
      "SELECT COUNT(*) FROM order_events WHERE order_id=? AND event_type='ORDER_AUTO_COMPLETED' AND scheduled_task_id=?",
      Integer.class, orderId, completeTaskId));

    receiptLifecycle.autoReceive(orderId, receiveTaskId);
    receiptLifecycle.autoComplete(orderId, completeTaskId);
    org.junit.jupiter.api.Assertions.assertEquals(2, jdbc.queryForObject(
      "SELECT COUNT(*) FROM order_events WHERE order_id=? AND event_type IN ('ORDER_AUTO_RECEIVED','ORDER_AUTO_COMPLETED')",
      Integer.class, orderId));
  }

  @Test
  void manualReceiptCancelsAutoReceiveAndCreatesOneAutoCompleteTask() {
    Long customerId = jdbc.queryForObject(
      "SELECT id FROM app_users WHERE email='demo@example.com'", Long.class);
    jdbc.update("INSERT INTO shop_orders(order_number,customer_id,status,total_amount,currency,payment_due_at,logistics_delivered_at,created_at,updated_at) VALUES('MANUAL-RECEIPT-ORDER',?,'SHIPPED',1299,'CNY',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", customerId);
    Long orderId = jdbc.queryForObject(
      "SELECT id FROM shop_orders WHERE order_number='MANUAL-RECEIPT-ORDER'", Long.class);
    receiptLifecycle.scheduleAutoReceive(orderId, Instant.now().plusSeconds(3600));

    receiptLifecycle.confirmReceipt(customerId, customerId, orderId);
    receiptLifecycle.confirmReceipt(customerId, customerId, orderId);

    org.junit.jupiter.api.Assertions.assertEquals("RECEIVED",
      jdbc.queryForObject("SELECT status FROM shop_orders WHERE id=?", String.class, orderId));
    org.junit.jupiter.api.Assertions.assertEquals("CANCELLED", jdbc.queryForObject(
      "SELECT status FROM scheduled_tasks WHERE task_type='AUTO_RECEIVE' AND task_key=?",
      String.class, "order:" + orderId));
    org.junit.jupiter.api.Assertions.assertEquals(1, jdbc.queryForObject(
      "SELECT COUNT(*) FROM scheduled_tasks WHERE task_type='AUTO_COMPLETE' AND task_key=?",
      Integer.class, "order:" + orderId));
    org.junit.jupiter.api.Assertions.assertEquals(1, jdbc.queryForObject(
      "SELECT COUNT(*) FROM order_events WHERE order_id=? AND event_type='ORDER_RECEIVED'",
      Integer.class, orderId));
  }

  @Test
  void manualAndAutomaticReceiptRaceProducesOneTransitionAndOneCompletionTask() throws Exception {
    Long customerId = jdbc.queryForObject(
      "SELECT id FROM app_users WHERE email='demo@example.com'", Long.class);
    jdbc.update("INSERT INTO shop_orders(order_number,customer_id,status,total_amount,currency,payment_due_at,logistics_delivered_at,created_at,updated_at) VALUES('RECEIPT-RACE-ORDER',?,'SHIPPED',1299,'CNY',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", customerId);
    Long orderId = jdbc.queryForObject(
      "SELECT id FROM shop_orders WHERE order_number='RECEIPT-RACE-ORDER'", Long.class);
    receiptLifecycle.scheduleAutoReceive(orderId, Instant.now().minusSeconds(2));
    Long receiveTaskId = jdbc.queryForObject(
      "SELECT id FROM scheduled_tasks WHERE task_type='AUTO_RECEIVE' AND task_key=?",
      Long.class, "order:" + orderId);

    CountDownLatch start = new CountDownLatch(1);
    ExecutorService workers = Executors.newFixedThreadPool(2);
    try {
      Future<?> manual = workers.submit(() -> {
        await(start);
        receiptLifecycle.confirmReceipt(customerId, customerId, orderId);
      });
      Future<Boolean> automatic = workers.submit(() -> {
        await(start);
        return scheduledTaskExecutor.execute(receiveTaskId, TaskTriggerSource.DATABASE_SCAN);
      });
      start.countDown();
      manual.get(10, TimeUnit.SECONDS);
      automatic.get(10, TimeUnit.SECONDS);
    } finally {
      workers.shutdownNow();
    }

    org.junit.jupiter.api.Assertions.assertEquals("RECEIVED",
      jdbc.queryForObject("SELECT status FROM shop_orders WHERE id=?", String.class, orderId));
    org.junit.jupiter.api.Assertions.assertEquals(1, jdbc.queryForObject(
      "SELECT COUNT(*) FROM order_events WHERE order_id=? AND event_type IN ('ORDER_RECEIVED','ORDER_AUTO_RECEIVED')",
      Integer.class, orderId));
    org.junit.jupiter.api.Assertions.assertEquals(1, jdbc.queryForObject(
      "SELECT COUNT(*) FROM scheduled_tasks WHERE task_type='AUTO_COMPLETE' AND task_key=?",
      Integer.class, "order:" + orderId));
    String receiveTaskStatus = jdbc.queryForObject(
      "SELECT status FROM scheduled_tasks WHERE id=?", String.class, receiveTaskId);
    org.junit.jupiter.api.Assertions.assertTrue(
      "CANCELLED".equals(receiveTaskStatus) || "SUCCEEDED".equals(receiveTaskStatus));
  }

  @Test
  void expiredProcessingLeaseIsRecoveredAndSafelyRetried() {
    int executionsBefore = reminderExecutions.get();
    var task = scheduledTasks.schedule(ScheduledTaskTypes.REVIEW_REMINDER,
      "lease-recovery-test", "ORDER", "1", Instant.now().minusSeconds(5), Map.of("orderId", 1));
    jdbc.update("UPDATE scheduled_tasks SET status='PROCESSING',attempts=1,claimed_by='crashed-worker',lease_until=? WHERE id=?",
      java.sql.Timestamp.from(Instant.now().minusSeconds(2)), task.getId());

    org.junit.jupiter.api.Assertions.assertEquals(1, scheduledTaskExecutor.recoverExpiredLeases(10));
    org.junit.jupiter.api.Assertions.assertEquals("FAILED",
      jdbc.queryForObject("SELECT status FROM scheduled_tasks WHERE id=?", String.class, task.getId()));
    org.junit.jupiter.api.Assertions.assertTrue(
      scheduledTaskExecutor.execute(task.getId(), TaskTriggerSource.DATABASE_SCAN));
    org.junit.jupiter.api.Assertions.assertEquals("SUCCEEDED",
      jdbc.queryForObject("SELECT status FROM scheduled_tasks WHERE id=?", String.class, task.getId()));
    org.junit.jupiter.api.Assertions.assertEquals(executionsBefore + 1, reminderExecutions.get());
    org.junit.jupiter.api.Assertions.assertEquals(1, jdbc.queryForObject(
      "SELECT COUNT(*) FROM scheduled_task_events WHERE task_id=? AND event_type='TASK_LEASE_EXPIRED'",
      Integer.class, task.getId()));
  }

  @Test
  void logisticsExceptionRequiresExplicitRecoveryAndDoesNotScheduleReceipt() throws Exception {
    Long customerId = jdbc.queryForObject(
      "SELECT id FROM app_users WHERE email='demo@example.com'", Long.class);
    jdbc.update("INSERT INTO shop_orders(order_number,customer_id,status,total_amount,currency,payment_due_at,created_at,updated_at) VALUES('LOGISTICS-EXCEPTION-ORDER',?,'SHIPPED',1299,'CNY',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", customerId);
    Long orderId = jdbc.queryForObject(
      "SELECT id FROM shop_orders WHERE order_number='LOGISTICS-EXCEPTION-ORDER'", Long.class);
    jdbc.update("INSERT INTO shipment_records(order_id,merchant_shipment_no,platform_shipment_no,carrier,tracking_number,status,shipped_at) VALUES(?,'MS-EXCEPTION','LGS-EXCEPTION','Demo Carrier','TRACK-EXCEPTION','IN_TRANSIT',CURRENT_TIMESTAMP)", orderId);

    acceptLogisticsNotice("EXCEPTION", "EXCEPTION", "Exception center", "Parcel held for review");
    org.junit.jupiter.api.Assertions.assertEquals("EXCEPTION", jdbc.queryForObject(
      "SELECT status FROM shipment_records WHERE order_id=?", String.class, orderId));

    acceptLogisticsNotice("PROGRESS", "AT_STATION", "Destination station", "Late progress callback");
    org.junit.jupiter.api.Assertions.assertEquals("EXCEPTION", jdbc.queryForObject(
      "SELECT status FROM shipment_records WHERE order_id=?", String.class, orderId));

    acceptLogisticsNotice("RECOVERY", "IN_TRANSIT", "Regional sorting center", "Transport resumed");
    org.junit.jupiter.api.Assertions.assertEquals("IN_TRANSIT", jdbc.queryForObject(
      "SELECT status FROM shipment_records WHERE order_id=?", String.class, orderId));
    org.junit.jupiter.api.Assertions.assertEquals(0, jdbc.queryForObject(
      "SELECT COUNT(*) FROM scheduled_tasks WHERE task_type='AUTO_RECEIVE' AND task_key=?",
      Integer.class, "order:" + orderId));
    org.junit.jupiter.api.Assertions.assertEquals(3, jdbc.queryForObject(
      "SELECT COUNT(*) FROM shipment_events WHERE shipment_id=(SELECT id FROM shipment_records WHERE order_id=?)",
      Integer.class, orderId));
  }

  @Test
  void logisticsReconciliationBackfillsMissingEventsIdempotently() {
    Long customerId = jdbc.queryForObject(
      "SELECT id FROM app_users WHERE email='demo@example.com'", Long.class);
    jdbc.update("INSERT INTO shop_orders(order_number,customer_id,status,total_amount,currency,payment_due_at,created_at,updated_at) VALUES('LOGISTICS-RECONCILIATION-ORDER',?,'SHIPPED',1299,'CNY',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", customerId);
    Long orderId = jdbc.queryForObject(
      "SELECT id FROM shop_orders WHERE order_number='LOGISTICS-RECONCILIATION-ORDER'", Long.class);
    jdbc.update("INSERT INTO shipment_records(order_id,merchant_shipment_no,platform_shipment_no,carrier,tracking_number,status,shipped_at) VALUES(?,'MS-RECONCILE','LGS-RECONCILE','Current Carrier','TRACK-RECONCILE','REQUESTED',CURRENT_TIMESTAMP)", orderId);
    Instant createdAt = Instant.now().minusSeconds(10);
    LogisticsGatewayClient.Result authoritative = new LogisticsGatewayClient.Result(
      1L, "MS-RECONCILE", "LGS-RECONCILE", "LOGISTICS-RECONCILIATION-ORDER",
      "Current Carrier", "TRACK-RECONCILE", "PICKED_UP", "http://callback",
      createdAt, createdAt.plusSeconds(5), List.of(
      new LogisticsGatewayClient.Event("RECONCILE-CREATED", "PROGRESS", "CREATED",
        "Warehouse", "Shipment created", createdAt),
      new LogisticsGatewayClient.Event("RECONCILE-PICKED", "PROGRESS", "PICKED_UP",
        "Origin", "Carrier picked up parcel", createdAt.plusSeconds(5))));

    var first = logisticsNotifications.reconcile(authoritative);
    var replay = logisticsNotifications.reconcile(authoritative);

    org.junit.jupiter.api.Assertions.assertEquals(2, first.processed());
    org.junit.jupiter.api.Assertions.assertEquals(2, replay.duplicates());
    org.junit.jupiter.api.Assertions.assertEquals("PICKED_UP", jdbc.queryForObject(
      "SELECT status FROM shipment_records WHERE order_id=?", String.class, orderId));
    org.junit.jupiter.api.Assertions.assertEquals(2, jdbc.queryForObject(
      "SELECT COUNT(*) FROM shipment_notifications WHERE shipment_id=(SELECT id FROM shipment_records WHERE order_id=?) AND processing_status='RECONCILED'",
      Integer.class, orderId));
  }

  @Test
  void outOfOrderAndSameRankLogisticsEventsNeverRepeatProjectionSideEffects() throws Exception {
    Long customerId = jdbc.queryForObject(
      "SELECT id FROM app_users WHERE email='demo@example.com'", Long.class);
    String suffix = UUID.randomUUID().toString().replace("-", "");
    String orderNumber = "LOGISTICS-ORDERING-" + suffix;
    String merchantNo = "MS-ORDERING-" + suffix;
    String platformNo = "LGS-ORDERING-" + suffix;
    jdbc.update("INSERT INTO shop_orders(order_number,customer_id,status,total_amount,currency,payment_due_at,created_at,updated_at) VALUES(?,?,'SHIPPED',1299,'CNY',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
      orderNumber, customerId);
    Long orderId = jdbc.queryForObject(
      "SELECT id FROM shop_orders WHERE order_number=?", Long.class, orderNumber);
    jdbc.update("INSERT INTO shipment_records(order_id,merchant_shipment_no,platform_shipment_no,carrier,tracking_number,status,shipped_at) VALUES(?,?,?,'Stable Carrier','TRACK-STABLE','OUT_FOR_DELIVERY',CURRENT_TIMESTAMP)",
      orderId, merchantNo, platformNo);

    acceptLogisticsNotice(merchantNo, platformNo, "PROGRESS", "DELIVERED",
      "Customer address", "First delivered event");
    acceptLogisticsNotice(merchantNo, platformNo, "PROGRESS", "DELIVERED",
      "Customer address", "Same-rank delivered event");
    acceptLogisticsNotice(merchantNo, platformNo, "PROGRESS", "CREATED",
      "Origin warehouse", "Late old event");

    org.junit.jupiter.api.Assertions.assertEquals("DELIVERED", jdbc.queryForObject(
      "SELECT status FROM shipment_records WHERE order_id=?", String.class, orderId));
    org.junit.jupiter.api.Assertions.assertEquals(3, jdbc.queryForObject(
      "SELECT COUNT(*) FROM shipment_events WHERE shipment_id=(SELECT id FROM shipment_records WHERE order_id=?)",
      Integer.class, orderId));
    org.junit.jupiter.api.Assertions.assertEquals(1, jdbc.queryForObject(
      "SELECT COUNT(*) FROM order_events WHERE order_id=? AND event_type='LOGISTICS_STATUS_UPDATED'",
      Integer.class, orderId));
    org.junit.jupiter.api.Assertions.assertEquals(1, jdbc.queryForObject(
      "SELECT COUNT(*) FROM scheduled_tasks WHERE task_type='AUTO_RECEIVE' AND task_key=?",
      Integer.class, "order:" + orderId));
    org.junit.jupiter.api.Assertions.assertEquals("Stable Carrier:TRACK-STABLE", jdbc.queryForObject(
      "SELECT CONCAT(carrier,':',tracking_number) FROM shipment_records WHERE order_id=?",
      String.class, orderId));
  }

  @Test
  void customerCannotAccessAnotherCustomersCartAddressOrOrder() throws Exception {
    Long ownerId = jdbc.queryForObject("SELECT id FROM app_users WHERE email='customer2@example.com'", Long.class);
    Long merchantId = jdbc.queryForObject("SELECT merchant_id FROM products WHERE id=1", Long.class);
    jdbc.update("MERGE INTO carts(user_id,created_at,updated_at) KEY(user_id) VALUES(?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", ownerId);
    Long cartId = jdbc.queryForObject("SELECT id FROM carts WHERE user_id=?", Long.class, ownerId);
    jdbc.update("MERGE INTO cart_items(cart_id,product_id,merchant_id,product_name,thumbnail_url,unit_price,quantity,created_at,updated_at) KEY(cart_id,product_id) VALUES(?,1,?,'Learning Laptop','thumb',1299,3,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", cartId, merchantId);
    Long cartItemId = jdbc.queryForObject("SELECT id FROM cart_items WHERE cart_id=? AND product_id=1", Long.class, cartId);
    jdbc.update("INSERT INTO shipping_addresses(user_id,recipient_name,phone,country_code,state_province,city,district,address_line1,address_line2,postal_code,label,is_default,created_at,updated_at) VALUES(?,'Owner','13800000000','CN','Shanghai','Shanghai','Pudong','Owner Road','','200000','Home',TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", ownerId);
    Long addressId = jdbc.queryForObject("SELECT MAX(id) FROM shipping_addresses WHERE user_id=?", Long.class, ownerId);
    jdbc.update("INSERT INTO shop_orders(order_number,customer_id,status,total_amount,currency,payment_due_at,created_at,updated_at) VALUES('OWNER-ORDER',?,'PENDING_PAYMENT',1299,'CNY',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", ownerId);
    Long orderId = jdbc.queryForObject("SELECT MAX(id) FROM shop_orders WHERE customer_id=?", Long.class, ownerId);

    CsrfContext other = login("demo@example.com", false);
    mvc.perform(put("/api/cart/items/{id}", cartItemId).session(other.session()).cookie(other.cookie())
        .header("X-XSRF-TOKEN", other.token()).contentType("application/json").content("{\"quantity\":9}"))
      .andExpect(status().isNotFound());
    mvc.perform(put("/api/addresses/{id}", addressId).session(other.session()).cookie(other.cookie())
        .header("X-XSRF-TOKEN", other.token()).contentType("application/json")
        .content("{\"recipientName\":\"Attacker\",\"phone\":\"13900000000\",\"countryCode\":\"CN\",\"stateProvince\":\"Beijing\",\"city\":\"Beijing\",\"district\":\"Haidian\",\"addressLine1\":\"Changed\",\"addressLine2\":\"\",\"postalCode\":\"100000\",\"label\":\"Other\",\"isDefault\":false}"))
      .andExpect(status().isNotFound());
    mvc.perform(get("/api/purchase-orders/{id}", orderId).session(other.session()))
      .andExpect(status().isNotFound());

    org.junit.jupiter.api.Assertions.assertEquals(3,
      jdbc.queryForObject("SELECT quantity FROM cart_items WHERE id=?", Integer.class, cartItemId));
    org.junit.jupiter.api.Assertions.assertEquals("Owner",
      jdbc.queryForObject("SELECT recipient_name FROM shipping_addresses WHERE id=?", String.class, addressId));
  }

  private CsrfContext login(String email, boolean rememberMe) throws Exception {
    CsrfContext csrf = csrfContext();
    var loginResult = mvc.perform(post("/api/auth/login").session(csrf.session()).cookie(csrf.cookie())
        .header("X-XSRF-TOKEN", csrf.token()).contentType("application/json")
        .content("{\"email\":\"" + email
          + "\",\"password\":\"demo-password\",\"remember\":" + rememberMe + "}"))
      .andExpect(status().isOk());
    if (rememberMe) loginResult.andExpect(cookie().exists("COMMERCEFLOW_REMEMBER_ME"));
    return csrf;
  }

  private CsrfContext csrfContext() throws Exception {
    MockHttpSession session = new MockHttpSession();
    MvcResult result = mvc.perform(get("/api/auth/csrf").session(session))
      .andExpect(status().isOk()).andExpect(jsonPath("$.headerName").value("X-XSRF-TOKEN"))
      .andReturn();
    String token = json.readTree(result.getResponse().getContentAsString()).get("token").asText();
    Cookie cookie = result.getResponse().getCookie("XSRF-TOKEN");
    if (cookie == null) throw new IllegalStateException("XSRF-TOKEN cookie was not issued");
    return new CsrfContext(session, cookie, token);
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Concurrent test start latch timed out");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Concurrent test was interrupted", exception);
    }
  }

  private void acceptLogisticsNotice(String eventType, String status, String location,
                                     String description) throws Exception {
    acceptLogisticsNotice("MS-EXCEPTION", "LGS-EXCEPTION", eventType, status, location, description);
  }

  private void acceptLogisticsNotice(String merchantShipmentNo, String platformShipmentNo,
                                     String eventType, String status, String location, String description) throws Exception {
    String raw = json.writeValueAsString(Map.of(
      "notificationId", UUID.randomUUID().toString(),
      "merchantShipmentNo", merchantShipmentNo,
      "platformShipmentNo", platformShipmentNo,
      "eventType", eventType,
      "status", status,
      "carrier", "Demo Carrier",
      "trackingNumber", "TRACK-EXCEPTION",
      "location", location,
      "description", description,
      "occurredAt", Instant.now().toString()));
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec("demo-logistics-callback-secret".getBytes(StandardCharsets.UTF_8),
      "HmacSHA256"));
    logisticsNotifications.accept(raw,
      Base64.getEncoder().encodeToString(mac.doFinal(raw.getBytes(StandardCharsets.UTF_8))));
  }

  private record CsrfContext(MockHttpSession session, Cookie cookie, String token) {
  }
}
