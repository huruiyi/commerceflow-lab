package vip.fairy.commerceflow.inventory.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.fairy.commerceflow.inventory.domain.InventoryMovementView;
import vip.fairy.commerceflow.inventory.domain.MerchantInventoryItem;
import vip.fairy.commerceflow.inventory.domain.ProductInventory;
import vip.fairy.commerceflow.inventory.infrastructure.persistence.InventoryMapper;
import vip.fairy.commerceflow.inventory.infrastructure.persistence.MerchantInventoryMapper;
import vip.fairy.commerceflow.inventory.infrastructure.persistence.WarehouseStockMapper;

@ExtendWith(MockitoExtension.class)
class MerchantInventoryServiceTest {

  @Mock
  private MerchantInventoryMapper merchantInventory;

  @Mock
  private InventoryMapper inventory;

  @Mock
  private WarehouseStockMapper warehouses;

  @Test
  void adjustsOwnedInventoryAndRecordsTheResultingBalance() {
    MerchantInventoryItem before = mock(MerchantInventoryItem.class);
    MerchantInventoryItem after = mock(MerchantInventoryItem.class);
    ProductInventory balance = new ProductInventory(1L, 45, 2, 3);
    when(merchantInventory.find(7L, 1L)).thenReturn(before, after);
    when(merchantInventory.adjust(7L, 1L, 5)).thenReturn(1);
    when(warehouses.adjust(1L, 1L, 5, 0, 0)).thenReturn(1);
    when(inventory.inventory(1L)).thenReturn(balance);

    MerchantInventoryItem result = service().adjust(7L, 1L, 5, " Cycle count ");

    assertThat(result).isSameAs(after);
    verify(inventory).insertWarehouseMovement(anyString(), eq(null), eq(1L), eq("MANUAL_INCREASE"),
      eq(5), eq(balance), eq("Cycle count"), eq(1L));
  }

  @Test
  void rejectsAnAdjustmentForAnotherMerchantsProduct() {
    when(merchantInventory.find(7L, 1L)).thenReturn(null);

    assertThatThrownBy(() -> service().adjust(7L, 1L, 1, "Cycle count"))
      .isInstanceOf(InventoryNotFoundException.class);
  }

  @Test
  void returnsOnlyTheInventoryRowsSelectedByMerchantScope() {
    MerchantInventoryItem item = mock(MerchantInventoryItem.class);
    when(merchantInventory.list(7L)).thenReturn(List.of(item));

    assertThat(service().list(7L)).containsExactly(item);
    verify(merchantInventory).list(7L);
  }

  @Test
  void pagesOwnedMovementHistoryBeyondTheLegacyLimit() {
    when(merchantInventory.find(7L, 1L)).thenReturn(mock(MerchantInventoryItem.class));
    when(merchantInventory.movementCount(7L, 1L)).thenReturn(104L);
    InventoryMovementView row = mock(InventoryMovementView.class);
    when(merchantInventory.movements(7L, 1L, 10, 100)).thenReturn(List.of(row));

    var page = service().movementPage(7L, 1L, 99, 10);

    assertThat(page.total()).isEqualTo(104);
    assertThat(page.page()).isEqualTo(10);
    assertThat(page.rows()).containsExactly(row);
    assertThatThrownBy(() -> service().movementPage(8L, 1L, 0, 10))
      .isInstanceOf(InventoryNotFoundException.class);
    verify(merchantInventory, never()).movementCount(8L, 1L);
  }

  private MerchantInventoryService service() {
    return new MerchantInventoryService(merchantInventory, inventory, warehouses);
  }
}
