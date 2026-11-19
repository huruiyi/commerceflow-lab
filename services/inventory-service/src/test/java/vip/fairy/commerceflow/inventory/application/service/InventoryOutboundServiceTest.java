package vip.fairy.commerceflow.inventory.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.inventory.application.contract.v1.InventoryOutboundV1.Document;
import vip.fairy.commerceflow.inventory.application.contract.v1.InventoryOutboundV1.Line;
import vip.fairy.commerceflow.inventory.domain.InventoryReservation;
import vip.fairy.commerceflow.inventory.domain.OutboundProductQuantity;
import vip.fairy.commerceflow.inventory.infrastructure.persistence.InventoryLineCreditMapper;
import vip.fairy.commerceflow.inventory.infrastructure.persistence.InventoryMapper;
import vip.fairy.commerceflow.inventory.infrastructure.persistence.InventoryOutboundMapper;

class InventoryOutboundServiceTest {

  private final InventoryMapper inventory = mock(InventoryMapper.class);
  private final InventoryLineCreditMapper credits = mock(InventoryLineCreditMapper.class);
  private final InventoryOutboundMapper store = mock(InventoryOutboundMapper.class);
  private final ObjectMapper mapper = new ObjectMapper();
  private final InventoryOutboundService service = new InventoryOutboundService(inventory, store, mapper, credits);

  @Test
  void wholeOrderPayloadKeepsTheExistingFingerprint() {
    String payload = "{\"outboundId\":\"00000000-0000-0000-0000-000000000001\",\"orderId\":40,\"merchantId\":5,"
      + "\"lines\":[{\"orderLineId\":33,\"productId\":7,\"quantity\":3}]}";

    Document document = mapper.readValue(payload, Document.class);

    assertThat(mapper.writeValueAsString(document)).isEqualTo(payload);
    assertThat(mapper.writeValueAsString(new Document(document.outboundId(), 40, 5, document.lines(), false)))
      .isEqualTo(payload);
    assertThat(mapper.writeValueAsString(new Document(document.outboundId(), 40, 5, document.lines(), true)))
      .endsWith("\"partial\":true}");
  }

  @Test
  void permitsTheRemainingQuantityOfTheSameLine() {
    Document document = packageDocument(2);
    when(store.claim(eq(document.outboundId().toString()), eq(40L), eq(5L), anyString())).thenReturn(1);
    when(inventory.lockByOrder(40L)).thenReturn(List.of(new InventoryReservation(1L, 7L, 3, "COMMITTED")));
    when(store.postedQuantities(40)).thenReturn(List.of(new OutboundProductQuantity(7, 1)));

    assertThat(service.post(document).outboundId()).isEqualTo(document.outboundId());
    verify(store).insertLine(document.outboundId().toString(), document.lines().getFirst());
  }

  @Test
  void rejectsAQuantityAlreadyPostedOrCreditedBeforeShipment() {
    Document document = packageDocument(2);
    when(store.claim(eq(document.outboundId().toString()), eq(40L), eq(5L), anyString())).thenReturn(1);
    when(inventory.lockByOrder(40L)).thenReturn(List.of(new InventoryReservation(1L, 7L, 3, "COMMITTED")));
    when(store.postedQuantities(40)).thenReturn(List.of(new OutboundProductQuantity(7, 1)));
    when(credits.creditedProduct(40L, 7L, "BEFORE_SHIPMENT")).thenReturn(1);

    assertThatThrownBy(() -> service.post(document)).isInstanceOf(InventoryBusinessRuleException.class);
    verify(store, never()).insertLine(anyString(), any());
  }

  private Document packageDocument(int quantity) {
    return new Document(UUID.randomUUID(), 40, 5, List.of(new Line(33, 7, quantity)), true);
  }
}
