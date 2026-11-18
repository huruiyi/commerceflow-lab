package vip.fairy.commerceflow.inventory.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.fairy.commerceflow.inventory.application.contract.v1.InventoryCommandTypeV1;
import vip.fairy.commerceflow.inventory.application.contract.v1.InventoryCommandV1;
import vip.fairy.commerceflow.inventory.infrastructure.persistence.InventoryCommandInboxMapper;

@ExtendWith(MockitoExtension.class)
class InventoryCommandServiceTest {

  @Mock
  private InventoryCommandInboxMapper inbox;

  @Mock
  private InventoryBalanceService inventory;

  @Test
  void reservesThroughTheSingleCommandUseCase() {
    InventoryCommandV1 command = reserve("command-1");
    when(inbox.claim(eq(command.commandId()), eq("RESERVE"), eq(command.orderId()), anyString()))
      .thenReturn(1);
    when(inbox.complete(command.commandId())).thenReturn(1);

    var result = service().handle(command);

    assertThat(result.outcome()).isEqualTo("SUCCEEDED");
    verify(inventory).reserve(command.orderId(), command.items());
  }

  @Test
  void recordsAndReplaysTheSameDefinitiveReservationRejection() {
    InventoryCommandV1 command = reserve("no-stock");
    when(inbox.claim(eq(command.commandId()), eq("RESERVE"), eq(command.orderId()), anyString()))
      .thenAnswer(invocation -> {
        when(inbox.payloadFingerprint(command.commandId())).thenReturn(invocation.getArgument(3));
        return 1;
      })
      .thenReturn(0);
    String message = "Inventory is unavailable for product 1";
    doThrow(new InventoryReservationRejectedException(1L))
      .when(inventory).reserve(command.orderId(), command.items());
    when(inbox.reject(command.commandId(), message)).thenReturn(1);
    when(inbox.rejectionMessage(command.commandId())).thenReturn(message);
    InventoryCommandService service = service();

    var initial = service.handle(command);
    var replay = service.handle(command);

    assertThat(initial.outcome()).isEqualTo("REJECTED");
    assertThat(replay.errorCode()).isEqualTo("STOCK_UNAVAILABLE");
    assertThat(replay.message()).isEqualTo(initial.message());
    verify(inventory, times(1)).reserve(command.orderId(), command.items());
  }

  @Test
  void rejectsAnInvalidTraceparentBeforeClaimingTheInbox() {
    InventoryCommandV1 valid = reserve("command-2");
    InventoryCommandV1 invalid = new InventoryCommandV1(valid.schemaVersion(), valid.commandId(),
      valid.commandType(), valid.occurredAt(), valid.producer(), valid.orderId(), valid.items(),
      valid.reason(), valid.correlationId(), valid.causationId(), "invalid");

    assertThatThrownBy(() -> service().handle(invalid))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("traceparent");
  }

  private InventoryCommandService service() {
    return new InventoryCommandService(inbox, inventory);
  }

  private InventoryCommandV1 reserve(String commandId) {
    return new InventoryCommandV1(1, commandId, InventoryCommandTypeV1.RESERVE, Instant.now(),
      "ordering", 100L, List.of(new InventoryCommandV1.Item(1L, "Notebook", 2)),
      "Checkout inventory reservation", commandId, commandId,
      "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01");
  }
}
