package vip.fairy.commerceflow.commerce.inventory.infrastructure.transport;

import java.time.Duration;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vip.fairy.commerceflow.commerce.inventory.application.InventoryCommandDeliveryStateMachine;
import vip.fairy.commerceflow.commerce.inventory.application.contract.v1.InventoryCommandV1;

@Component
public class InventoryCommandOutboxDispatcher {

  private static final Logger LOG = LoggerFactory.getLogger(InventoryCommandOutboxDispatcher.class);

  private final InventoryCommandDeliveryStateMachine deliveries;
  private final InventoryRemoteCommandClient remote;
  private final Tracer tracer;
  private final Duration resultTimeout;
  private final Duration retryDelay;
  private final int maxAttempts;
  private final int batchSize;

  public InventoryCommandOutboxDispatcher(
    InventoryCommandDeliveryStateMachine deliveries,
    InventoryRemoteCommandClient remote,
    Tracer tracer,
    @Value("${commerceflow.inventory.delivery.result-timeout:5s}") Duration resultTimeout,
    @Value("${commerceflow.inventory.delivery.retry-delay:1s}") Duration retryDelay,
    @Value("${commerceflow.inventory.delivery.max-attempts:5}") int maxAttempts,
    @Value("${commerceflow.inventory.delivery.batch-size:50}") int batchSize
  ) {
    this.deliveries = deliveries;
    this.remote = remote;
    this.tracer = tracer;
    this.resultTimeout = resultTimeout;
    this.retryDelay = retryDelay;
    this.maxAttempts = maxAttempts;
    this.batchSize = batchSize;
  }

  @Scheduled(fixedDelayString = "${commerceflow.inventory.delivery.dispatch-delay:250ms}",
    initialDelayString = "${commerceflow.inventory.delivery.dispatch-initial-delay:1s}")
  public void dispatch() {
    deliveries.dispatchable(batchSize).forEach(this::deliver);
  }

  @Scheduled(fixedDelayString = "${commerceflow.inventory.delivery.recovery-delay:1s}",
    initialDelayString = "${commerceflow.inventory.delivery.recovery-initial-delay:2s}")
  public void recoverExpired() {
    deliveries.recoverExpired(maxAttempts, retryDelay);
  }

  public boolean deliver(String commandId) {
    if (deliveries.markInFlight(commandId, resultTimeout) != 1) {
      return false;
    }
    InventoryCommandV1 command = deliveries.command(commandId);
    Span span = dispatchSpan(command);
    try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
      return send(command, span);
    } finally {
      span.end();
    }
  }

  private boolean send(InventoryCommandV1 command, Span span) {
    try {
      deliveries.acknowledge(command, remote.send(command));
      span.tag("inventory.command.result", "ACKNOWLEDGED");
      return true;
    } catch (InventoryRemoteUnavailableException exception) {
      span.error(exception);
      span.tag("inventory.command.result", "UNKNOWN");
      LOG.warn("Inventory command {} has an unknown remote result; awaiting deadline",
        command.commandId(), exception);
      return false;
    }
  }

  private Span dispatchSpan(InventoryCommandV1 command) {
    String[] fields = command.traceparent().split("-");
    TraceContext parent = tracer.traceContextBuilder()
      .traceId(fields[1])
      .spanId(fields[2])
      .sampled((Integer.parseInt(fields[3], 16) & 1) == 1)
      .build();
    return tracer.spanBuilder()
      .setParent(parent)
      .name("inventory.command.dispatch")
      .tag("inventory.command.id", command.commandId())
      .tag("inventory.command.type", command.commandType().name())
      .start();
  }
}
