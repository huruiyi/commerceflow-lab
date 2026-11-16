package vip.fairy.commerceflow.commerce.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;

import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import vip.fairy.commerceflow.commerce.inventory.application.InventoryCommandDeliveryStateMachine;
import vip.fairy.commerceflow.commerce.inventory.application.contract.v1.InventoryCommandResultV1;
import vip.fairy.commerceflow.commerce.inventory.application.contract.v1.InventoryCommandV1;
import vip.fairy.commerceflow.commerce.inventory.infrastructure.transport.InventoryCommandOutboxDispatcher;
import vip.fairy.commerceflow.commerce.inventory.infrastructure.transport.InventoryRemoteCommandClient;
import vip.fairy.commerceflow.commerce.inventory.infrastructure.transport.InventoryRemoteUnavailableException;

class InventoryCommandOutboxDispatcherTest {

  private final InventoryCommandDeliveryStateMachine deliveries =
    mock(InventoryCommandDeliveryStateMachine.class);
  private final InventoryRemoteCommandClient remote = mock(InventoryRemoteCommandClient.class);
  private final InventoryCommandOutboxDispatcher dispatcher = new InventoryCommandOutboxDispatcher(
    deliveries, remote, Tracer.NOOP, Duration.ofSeconds(5), Duration.ofSeconds(1), 5, 50);

  @Test
  void acknowledgesAnExplicitRemoteResult() {
    InventoryCommandV1 command = InventoryCommandV1.commit("command-1", 1L, "payment-1");
    InventoryCommandResultV1 result = InventoryCommandResultV1.succeeded(command);
    when(deliveries.markInFlight(command.commandId(), Duration.ofSeconds(5))).thenReturn(1);
    when(deliveries.command(command.commandId())).thenReturn(command);
    when(remote.send(command)).thenReturn(result);

    assertThat(dispatcher.deliver(command.commandId())).isTrue();

    verify(deliveries).acknowledge(command, result);
  }

  @Test
  void leavesAnUnknownRemoteResultInFlightUntilItsDeadline() {
    InventoryCommandV1 command = InventoryCommandV1.release(
      "command-2", 2L, "cancelled", "order-event-2");
    when(deliveries.markInFlight(command.commandId(), Duration.ofSeconds(5))).thenReturn(1);
    when(deliveries.command(command.commandId())).thenReturn(command);
    when(remote.send(command)).thenThrow(new InventoryRemoteUnavailableException(
      new IllegalStateException("connection lost")));

    assertThat(dispatcher.deliver(command.commandId())).isFalse();

    verify(deliveries, never()).acknowledge(any(), any());
  }
}
