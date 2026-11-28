package vip.fairy.commerceflow.commerce.refund.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.fairy.commerceflow.commerce.ordering.application.port.OrderRefundPort;
import vip.fairy.commerceflow.commerce.refund.domain.LineAfterSale;
import vip.fairy.commerceflow.commerce.refund.infrastructure.LineAfterSaleMapper;
import vip.fairy.commerceflow.commerce.shared.application.BusinessRuleException;

@ExtendWith(MockitoExtension.class)
class LineAfterSaleRequestContractTest {

  private static final UUID REQUEST_ID = UUID.fromString("5879afc7-eebf-4dd2-abac-3ca9fd01f641");
  private static final String OUTBOUND_ID = "386d764c-fe75-40e6-bc75-74bfac6c7e81";
  private static final String REASON = "Return request";

  @Mock
  private LineAfterSaleMapper cases;

  @Mock
  private OrderRefundPort orders;

  @InjectMocks
  private LineAfterSaleService service;

  private final LineAfterSale original = new LineAfterSale(REQUEST_ID.toString(), 10L, 20L, 30L, 40L, 50L,
    1, new BigDecimal("20.00"), new BigDecimal("0.00"), "CNY", "RETURN", "APPLIED", REASON,
    null, null, null, null, null, null, 1L, OUTBOUND_ID, null, null, null, null);

  @BeforeEach
  void findRecordedRequest() {
    when(cases.find(REQUEST_ID.toString())).thenReturn(original);
  }

  @Test
  void sameApplicationReturnsTheOriginalCaseWithoutRepricingOrInserting() {
    assertThat(service.submit(40L, 10L, 20L, 1, REQUEST_ID, REASON, 1L, OUTBOUND_ID, false)).isSameAs(original);
    verify(cases, never()).insert(any());
    verifyNoInteractions(orders);
  }

  @ParameterizedTest
  @CsvSource({"40, 10, 21, 1, Return request, 1, false", "40, 10, 20, 2, Return request, 1, false",
    "40, 10, 20, 1, Revised reason, 1, false", "40, 10, 20, 1, Return request, 2, false",
    "40, 10, 20, 1, Return request, 1, true", "41, 10, 20, 1, Return request, 1, false",
    "40, 11, 20, 1, Return request, 1, false"})
  void changedApplicationCannotReuseAnExistingRequestId(long customerId, long orderId, long lineId, int quantity,
                                                         String reason, long warehouseId, boolean exchange) {
    assertThatThrownBy(() -> service.submit(customerId, orderId, lineId, quantity, REQUEST_ID, reason,
      warehouseId, OUTBOUND_ID, exchange)).isInstanceOf(BusinessRuleException.class)
      .hasMessage("After-sales request identifier is already in use");
    verify(cases, never()).insert(any());
    verifyNoInteractions(orders);
  }
}
