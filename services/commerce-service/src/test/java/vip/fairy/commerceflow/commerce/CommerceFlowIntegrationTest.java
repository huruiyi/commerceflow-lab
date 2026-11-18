package vip.fairy.commerceflow.commerce;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import vip.fairy.commerceflow.commerce.identity.domain.AppUser;
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
import vip.fairy.commerceflow.commerce.ordering.application.contract.v1.OrderCommandV1;
import vip.fairy.commerceflow.commerce.ordering.application.contract.v1.OrderResultV1;
import vip.fairy.commerceflow.commerce.logistics.application.LogisticsNotificationService;
import vip.fairy.commerceflow.commerce.logistics.application.LogisticsGatewayClient;
import vip.fairy.commerceflow.commerce.logistics.application.contract.LogisticsFulfillmentV1;
import vip.fairy.commerceflow.commerce.logistics.application.contract.LogisticsFulfillmentV1.Command;
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
import vip.fairy.commerceflow.commerce.fulfillment.application.FulfillmentAdmission;
import vip.fairy.commerceflow.commerce.fulfillment.domain.MerchantFulfillmentJob;
import vip.fairy.commerceflow.commerce.fulfillment.domain.ShipmentJob;
import vip.fairy.commerceflow.commerce.fulfillment.domain.ShipmentJob.Status;
import vip.fairy.commerceflow.commerce.fulfillment.infrastructure.MerchantFulfillmentJobMapper;
import vip.fairy.commerceflow.commerce.fulfillment.infrastructure.ShipmentJobMapper;
import vip.fairy.commerceflow.commerce.inventory.application.contract.v1.InventoryCommandResultOutcomeV1;
import vip.fairy.commerceflow.commerce.inventory.application.contract.v1.InventoryCommandResultV1;
import vip.fairy.commerceflow.commerce.inventory.application.contract.v1.InventoryCommandSemantics;
import vip.fairy.commerceflow.commerce.inventory.domain.InventoryMovementView;
import vip.fairy.commerceflow.commerce.inventory.domain.MerchantInventoryItem;
import vip.fairy.commerceflow.commerce.inventory.infrastructure.transport.MerchantInventoryRemoteClient;
import vip.fairy.commerceflow.commerce.ordering.application.PurchaseOrderService;
import vip.fairy.commerceflow.commerce.ordering.api.CreatePaymentRequest;
import vip.fairy.commerceflow.commerce.shared.application.BusinessRuleException;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.PaymentChannelV1;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.PaymentCloseCommandV1;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.PaymentCloseResultV1;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.PaymentCloseOutcomeV1;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.PaymentContractSemantics;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.PaymentCreateCommandV1;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.PaymentQueryV1;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.PaymentResultV1;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.PaymentOrchestrationV1;
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
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.session.MapSessionRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;
import vip.fairy.commerceflow.commerce.shared.application.ExternalServiceUnavailableException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import vip.fairy.commerceflow.commerce.ordering.application.port.OrderStorePort;

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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
  "commerceflow.fulfillment.merchant-operation-delay=1h",
  "commerceflow.fulfillment.shipment-delay=1h"
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
    @Primary
    OrderStorePort testOrderStore(JdbcTemplate jdbc, SqlSessionTemplate session) {
      return new TestOrderStoreAdapter(jdbc, session);
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
  OrderCancellationService orderCancellations;
  @Autowired
  PurchaseOrderService purchaseOrders;
  @Autowired
  InventoryCommandPort inventoryCommands;

  @MockitoBean
  InventoryCommandPort.ImmediateDelivery immediateInventoryDelivery;
  @Autowired
  InventoryCommandValidatorV1 inventoryCommandValidator;
  @Autowired
  InventoryCommandDeliveryStateMachine inventoryDeliveryStateMachine;
  @Autowired
  InventoryCommandRecoveryService inventoryCommandRecoveries;
  @Autowired
  MerchantFulfillmentService merchantFulfillment;
  @Autowired
  MerchantFulfillmentJobMapper merchantJobs;
  @Autowired
  ShipmentJobMapper shipmentJobs;
  @Autowired
  FulfillmentAdmission fulfillmentAdmission;
  @Autowired
  LogisticsFulfillmentV1 logisticsFulfillment;
  @Autowired
  CustomerNotificationCommandRouter notificationRouter;
  @Autowired
  NotificationOutboxDispatcher notificationOutbox;
  @Autowired
  NotificationDeliveryProperties notificationDelivery;
  @Autowired
  AppUserMapper users;
  @MockitoBean
  PaymentOrchestrationV1 paymentOrchestration;
  @MockitoBean
  LogisticsGatewayClient logisticsGateway;
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
    when(paymentOrchestration.closePending(any())).thenAnswer(invocation -> {
      PaymentCloseCommandV1 command = invocation.getArgument(0);
      return new PaymentCloseResultV1(PaymentCloseResultV1.VERSION,
        UUID.randomUUID().toString(), PaymentCloseOutcomeV1.CLOSED, Instant.now(),
        "payment-orchestration-service", command.orderId(), 0, "No pending payment",
        command.correlationId(), command.commandId(), command.traceparent());
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
  void paymentV1ContractFixturesRemainCompatible() throws Exception {
    try (var createFixture = getClass().getResourceAsStream(
      "/contracts/payment/payment-create-command-v1.json");
         var closeFixture = getClass().getResourceAsStream(
           "/contracts/payment/payment-close-command-v1.json");
         var resultFixture = getClass().getResourceAsStream(
           "/contracts/payment/payment-close-result-v1.json");
         var callbackFixture = getClass().getResourceAsStream(
           "/contracts/payment/payment-success-result-v1.json")) {
      org.junit.jupiter.api.Assertions.assertNotNull(createFixture);
      org.junit.jupiter.api.Assertions.assertNotNull(closeFixture);
      org.junit.jupiter.api.Assertions.assertNotNull(resultFixture);
      org.junit.jupiter.api.Assertions.assertNotNull(callbackFixture);
      PaymentCreateCommandV1 create = json.readValue(createFixture, PaymentCreateCommandV1.class);
      PaymentCloseCommandV1 close = json.readValue(closeFixture, PaymentCloseCommandV1.class);
      PaymentCloseResultV1 result = json.readValue(resultFixture, PaymentCloseResultV1.class);
      PaymentResultV1 callback = json.readValue(callbackFixture, PaymentResultV1.class);
      create.validate();
      close.validate();
      result.validate();
      callback.validate();
      new PaymentQueryV1(PaymentQueryV1.VERSION, create.orderId()).validate();
      org.junit.jupiter.api.Assertions.assertEquals(64,
        PaymentContractSemantics.fingerprint(create).length());
      org.junit.jupiter.api.Assertions.assertEquals(64,
        PaymentContractSemantics.fingerprint(close).length());
      org.junit.jupiter.api.Assertions.assertEquals(64,
        PaymentContractSemantics.fingerprint(callback).length());
    }
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
      () -> merchantFulfillment.ship(merchantId, orderId,
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
  void merchantRecoveryPreservesOriginalCommandAndFencesCompetingWorkers() throws Exception {
    String command = UUID.randomUUID().toString();
    merchantJobs.insert(new MerchantFulfillmentJob(command, 90_001L, 5L,
      MerchantFulfillmentJob.Operation.UPDATE_SHIPMENT, 2, "Priority", "RECOVERY-TEST", "old -> new",
      MerchantFulfillmentJob.Step.CHANNEL, Status.PENDING, null));
    String firstOwner = UUID.randomUUID().toString();
    String secondOwner = UUID.randomUUID().toString();
    CountDownLatch start = new CountDownLatch(1);
    try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
      Future<Integer> first = workers.submit(() -> {
        await(start);
        return merchantJobs.claim(command, firstOwner, true);
      });
      Future<Integer> second = workers.submit(() -> {
        await(start);
        return merchantJobs.claim(command, secondOwner, true);
      });
      start.countDown();
      int firstResult = first.get(5, TimeUnit.SECONDS);
      assertEquals(1, firstResult + second.get(5, TimeUnit.SECONDS));
      String owner = firstResult == 1 ? firstOwner : secondOwner;
      String loser = firstResult == 1 ? secondOwner : firstOwner;
      assertEquals(0, merchantJobs.advanceToOrder(command, loser));
      assertEquals(1, merchantJobs.advanceToOrder(command, owner));
      assertEquals(0, merchantJobs.finish(command, loser, "COMPLETED", null));
      assertEquals(1, merchantJobs.finish(command, owner, "REVIEW_REQUIRED", "ORDER_ATTEMPT_FAILED"));
    }

    String base = "/api/admin/fulfillment/merchant-operations";
    String operation = base + "/" + command;
    String recovery = UUID.randomUUID().toString();
    String body = json.writeValueAsString(Map.of("recoveryId", recovery,
      "reason", "Check order arrival", "evidence", "Channel retained version 3"));
    CsrfContext merchant = login("merchant@example.com", false);
    mvc.perform(post(operation + "/retry").session(merchant.session())
        .cookie(merchant.cookie()).header("X-XSRF-TOKEN", merchant.token())
        .contentType("application/json").content(body))
      .andExpect(status().isForbidden());
    CsrfContext admin = login("admin@example.com", false);
    mvc.perform(get(base).session(admin.session()))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$[0].commandId").value(command));
    for (int attempt = 0; attempt < 2; attempt++) {
      mvc.perform(post(operation + "/retry").session(admin.session())
          .cookie(admin.cookie()).header("X-XSRF-TOKEN", admin.token())
          .contentType("application/json").content(body))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.commandId").value(command))
        .andExpect(jsonPath("$.step").value("ORDER"))
        .andExpect(jsonPath("$.previousError").value("ORDER_ATTEMPT_FAILED"));
    }
    mvc.perform(post(operation + "/retry").session(admin.session())
        .cookie(admin.cookie()).header("X-XSRF-TOKEN", admin.token())
        .contentType("application/json").content(body.replace("version 3", "version 4")))
      .andExpect(status().isConflict());
    mvc.perform(get(operation).session(admin.session()))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.operation.status").value("PENDING"))
      .andExpect(jsonPath("$.operation.expectedVersion").value(2))
      .andExpect(jsonPath("$.recoveries.length()").value(1));
    String nextOwner = UUID.randomUUID().toString();
    assertEquals(1, merchantJobs.claim(command, nextOwner, true));
    assertEquals(0, merchantJobs.finish(command, firstOwner, "COMPLETED", null));
    assertEquals(0, merchantJobs.finish(command, secondOwner, "COMPLETED", null));
    assertEquals(1, merchantJobs.finish(command, nextOwner, "COMPLETED", null));
  }

  @Test
  @Sql("/fulfillment/freeze-admission.sql")
  @Sql(scripts = "/fulfillment/reopen-admission.sql", executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
  void frozenFulfillmentAdmissionDoesNotRegisterNewCommands() {
    long order = 90_008L;
    String confirmId = UUID.randomUUID().toString();
    MerchantFulfillmentJob confirmation = new MerchantFulfillmentJob(confirmId, order, 5L,
      MerchantFulfillmentJob.Operation.CONFIRM, 0, null, null, null,
      MerchantFulfillmentJob.Step.ORDER, Status.PENDING, null);
    String shipId = UUID.randomUUID().toString();
    ShipmentJob shipment = new ShipmentJob(order, shipId, 5L, "FROZEN-ORDER", "Priority", "FROZEN",
      ShipmentJob.Step.RESERVE, Status.PENDING, null);
    assertEquals("FULFILLMENT_WRITE_FROZEN",
      assertThrows(ExternalServiceUnavailableException.class,
        () -> fulfillmentAdmission.merchant(confirmation, true)).errorCode());
    assertEquals("FULFILLMENT_WRITE_FROZEN",
      assertThrows(ExternalServiceUnavailableException.class,
        () -> fulfillmentAdmission.shipment(shipment, true)).errorCode());
    org.junit.jupiter.api.Assertions.assertNull(fulfillmentAdmission.find(confirmId));
    org.junit.jupiter.api.Assertions.assertNull(fulfillmentAdmission.find(shipId));
  }

  @Test
  void fulfillmentAdmissionSerializesDifferentOperationsForTheSameOrder() throws Exception {
    Long order = 90_002L;
    String confirmId = UUID.randomUUID().toString();
    String shipId = UUID.randomUUID().toString();
    MerchantFulfillmentJob confirmation = new MerchantFulfillmentJob(confirmId, order, 5L,
      MerchantFulfillmentJob.Operation.CONFIRM, 0, null, null, null,
      MerchantFulfillmentJob.Step.ORDER, Status.PENDING, null);
    ShipmentJob shipment = new ShipmentJob(order, shipId, 5L, "ADMISSION-RACE", "Priority", "RACE",
      ShipmentJob.Step.RESERVE, Status.PENDING, null);
    CountDownLatch start = new CountDownLatch(1);
    try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
      Future<Boolean> confirm = workers.submit(() -> attemptAdmission(start,
        () -> fulfillmentAdmission.merchant(confirmation, true)));
      Future<Boolean> ship = workers.submit(() -> attemptAdmission(start,
        () -> fulfillmentAdmission.shipment(shipment, false)));
      start.countDown();
      boolean confirmationWon = confirm.get(5, TimeUnit.SECONDS);
      assertEquals(!confirmationWon, ship.get(5, TimeUnit.SECONDS));
      String owner = UUID.randomUUID().toString();
      if (confirmationWon) {
        assertEquals(1, merchantJobs.claim(confirmId, owner, true));
        assertEquals(1, merchantJobs.finish(confirmId, owner, "REVIEW_REQUIRED", "ORDER_ATTEMPT_FAILED"));
        assertThrows(BusinessRuleException.class, () -> fulfillmentAdmission.shipment(shipment, false));
        assertEquals(false, fulfillmentAdmission.merchant(confirmation, true).created());
        assertEquals(1, merchantJobs.requeueReview(confirmId));
        assertEquals(1, merchantJobs.claim(confirmId, owner, true));
        assertEquals(1, merchantJobs.finish(confirmId, owner, "COMPLETED", null));
        assertEquals(true, fulfillmentAdmission.shipment(shipment, false).created());
      } else {
        assertEquals(1, shipmentJobs.claim(shipId, owner, true));
        assertEquals(1, shipmentJobs.finish(shipId, owner, "REVIEW_REQUIRED", "RESERVE_ATTEMPT_FAILED"));
        assertThrows(BusinessRuleException.class, () -> fulfillmentAdmission.merchant(confirmation, true));
        assertEquals(false, fulfillmentAdmission.shipment(shipment, false).created());
        assertEquals(1, shipmentJobs.requeueReview(shipId));
        assertEquals(1, shipmentJobs.claim(shipId, owner, true));
        assertEquals(1, shipmentJobs.finish(shipId, owner, "COMPLETED", null));
        assertEquals(true, fulfillmentAdmission.merchant(confirmation, true).created());
      }
    }
  }

  @Test
  void knownReservationRejectionRetainsHistoryAndAdmitsOnlyOneNewCommand() throws Exception {
    Long orderId = 90_005L;
    String oldId = UUID.randomUUID().toString();
    ShipmentJob old = new ShipmentJob(orderId, oldId, 5L, "REJECTED-RACE", "Priority", "ATTEMPT",
      ShipmentJob.Step.RESERVE, Status.PENDING, null);
    fulfillmentAdmission.shipment(old, true);
    String owner = UUID.randomUUID().toString();
    assertEquals(1, shipmentJobs.claim(oldId, owner, true));
    assertEquals(1, shipmentJobs.finish(oldId, owner, "REJECTED", "ORDER_SHIPMENT_ADMISSION_REJECTED"));
    CountDownLatch start = new CountDownLatch(1);
    String firstId = UUID.randomUUID().toString();
    String secondId = UUID.randomUUID().toString();
    try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
      Future<Boolean> first = workers.submit(() -> attemptAdmission(start, () ->
        fulfillmentAdmission.shipment(new ShipmentJob(orderId, firstId, 5L, old.orderNumber(),
          "Priority", "NEW-A", ShipmentJob.Step.RESERVE, Status.PENDING, null), true)));
      Future<Boolean> second = workers.submit(() -> attemptAdmission(start, () ->
        fulfillmentAdmission.shipment(new ShipmentJob(orderId, secondId, 5L, old.orderNumber(),
          "Priority", "NEW-B", ShipmentJob.Step.RESERVE, Status.PENDING, null), true)));
      start.countDown();
      boolean firstWon = first.get(5, TimeUnit.SECONDS);
      assertEquals(!firstWon, second.get(5, TimeUnit.SECONDS));
      assertEquals(firstWon ? firstId : secondId, shipmentJobs.find(orderId).commandId());
      assertEquals(Status.REJECTED, fulfillmentAdmission.find(oldId).status());
    }
  }

  @Test
  void logisticsCommandKeepsOriginalReceiptAfterLostResponseAndNewerVersion() {
    Long order = 90_006L;
    String carrier = "Priority";
    String reference = "CFL-ORDER-" + order;
    String platform = "LGS-V45-TEST";
    Instant now = Instant.now();
    Command create = new Command(UUID.randomUUID().toString(), LogisticsFulfillmentV1.Operation.ENSURE_SHIPMENT,
      order, "LOGISTICS-V45", carrier, "FIRST", null);
    when(logisticsGateway.create(order, create.orderNumber(), carrier, "FIRST")).thenReturn(
      new LogisticsGatewayClient.Result(1L, reference, platform, create.orderNumber(), carrier, "FIRST",
        "CREATED", null, now, now, 0, List.of()));
    assertEquals(0L, logisticsFulfillment.execute(create).shipmentVersion());
    Command first = new Command(UUID.randomUUID().toString(), LogisticsFulfillmentV1.Operation.UPDATE_DETAILS,
      order, null, carrier, "SECOND", 0L);
    when(logisticsGateway.update(order, carrier, "SECOND", 0L))
      .thenThrow(new BusinessRuleException("Simulated lost response"))
      .thenReturn(new LogisticsGatewayClient.Result(1L, reference, platform, create.orderNumber(), carrier,
        "SECOND", "CREATED", null, now, now, 1, List.of()));
    assertThrows(BusinessRuleException.class, () -> logisticsFulfillment.execute(first));
    assertEquals(true, logisticsFulfillment.findReceipt(first.commandId()).isEmpty());
    var receipt = logisticsFulfillment.execute(first);
    assertEquals(1L, receipt.shipmentVersion());
    Command later = new Command(UUID.randomUUID().toString(), LogisticsFulfillmentV1.Operation.UPDATE_DETAILS,
      order, null, carrier, "THIRD", 1L);
    when(logisticsGateway.update(order, carrier, "THIRD", 1L)).thenReturn(
      new LogisticsGatewayClient.Result(1L, reference, platform, create.orderNumber(), carrier, "THIRD",
        "CREATED", null, now, now, 2, List.of()));
    assertEquals(2L, logisticsFulfillment.execute(later).shipmentVersion());
    assertEquals(receipt, logisticsFulfillment.findReceipt(first.commandId()).orElseThrow());
    assertEquals(receipt, logisticsFulfillment.execute(first));
    assertThrows(BusinessRuleException.class, () -> logisticsFulfillment.execute(
      new Command(first.commandId(), first.operation(), order, null, carrier, "DIFFERENT", 0L)));
    org.mockito.Mockito.verify(logisticsGateway, org.mockito.Mockito.times(2)).update(order, carrier, "SECOND", 0L);
  }

  @Test
  void fulfillmentV1ReplaysNormalizedCommandAndEnforcesSessionOwnership() throws Exception {
    Long merchantId = users.selectOne(Wrappers.lambdaQuery(AppUser.class)
      .eq(AppUser::getEmail, "merchant@example.com")).getId();
    Long orderId = 90_003L;
    String command = UUID.randomUUID().toString();
    fulfillmentAdmission.merchant(new MerchantFulfillmentJob(command, orderId, merchantId,
      MerchantFulfillmentJob.Operation.UPDATE_SHIPMENT, 2, "Priority", "V1-TRACK", "old -> new",
      MerchantFulfillmentJob.Step.CHANNEL, Status.PENDING, null), true);
    String path = "/api/merchant/orders/fulfillment-v1/commands";
    String body = json.writeValueAsString(Map.of("version", 1, "commandId", command, "operation", "UPDATE_SHIPMENT",
      "merchantId", merchantId, "orderId", orderId, "occurredAt", Instant.now().toString(), "carrier", " Priority ",
      "trackingNumber", " V1-TRACK ", "expectedShipmentVersion", 2));
    CsrfContext merchant = login("merchant@example.com", false);
    mvc.perform(post(path).session(merchant.session()).cookie(merchant.cookie())
        .header("X-XSRF-TOKEN", merchant.token()).contentType("application/json").content(body))
      .andExpect(status().isAccepted())
      .andExpect(jsonPath("$.commandId").value(command))
      .andExpect(jsonPath("$.status").value("PENDING"));
    mvc.perform(post(path).session(merchant.session()).cookie(merchant.cookie())
        .header("X-XSRF-TOKEN", merchant.token()).contentType("application/json")
        .content(body.replace("V1-TRACK", "CHANGED")))
      .andExpect(status().isConflict());
    mvc.perform(post(path).session(merchant.session()).cookie(merchant.cookie())
        .header("X-XSRF-TOKEN", merchant.token()).contentType("application/json")
        .content(body.replace("UPDATE_SHIPMENT", "SHIP")))
      .andExpect(status().isBadRequest());
    MvcResult queried = mvc.perform(get(path + "/{commandId}", command).session(merchant.session()))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.operation").value("UPDATE_SHIPMENT")).andReturn();
    assertEquals(command, json.readTree(queried.getResponse().getContentAsString()).path("commandId").asText());
    CsrfContext customer = login("demo@example.com", false);
    mvc.perform(get(path + "/{commandId}", command).session(customer.session()))
      .andExpect(status().isForbidden());
  }

  private boolean attemptAdmission(CountDownLatch start, Runnable attempt) {
    await(start);
    try {
      attempt.run();
      return true;
    } catch (BusinessRuleException conflict) {
      return false;
    }
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
      .andExpect(jsonPath("$.availableActions[?(@ == 'PAY')]").doesNotExist())
      .andExpect(jsonPath("$.availableActions[?(@ == 'CANCEL_ORDER')]").exists());

    org.junit.jupiter.api.Assertions.assertTrue(
      scheduledTaskExecutor.execute(task.getId(), TaskTriggerSource.DATABASE_SCAN));
    org.junit.jupiter.api.Assertions.assertEquals("CANCELLED",
      jdbc.queryForObject("SELECT status FROM shop_orders WHERE id=?", String.class, orderId));
    org.junit.jupiter.api.Assertions.assertEquals(1, jdbc.queryForObject(
      "SELECT COUNT(*) FROM order_events WHERE order_id=? AND event_type='ORDER_CANCELLED' AND trigger_source='SCHEDULED_TASK' AND scheduled_task_id=?",
      Integer.class, orderId, task.getId()));
    org.junit.jupiter.api.Assertions.assertEquals(1, jdbc.queryForObject(
      "SELECT COUNT(*) FROM order_effect_outbox WHERE aggregate_id=? AND event_type='ORDER_CANCELLED'",
      Integer.class, orderId.toString()));
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
        new CreatePaymentRequest("illegal-pay-" + suffix, false, PaymentChannelV1.ALIPAY)));
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
      "SELECT event_id,event_type,aggregate_id,payload FROM order_effect_outbox WHERE aggregate_id=? AND event_type='ORDER_CANCELLED' ORDER BY id DESC LIMIT 1",
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
      createdAt, createdAt.plusSeconds(5), 0L, List.of(
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
