package vip.fairy.commerceflow.simulator.logistics.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import vip.fairy.commerceflow.simulator.logistics.infrastructure.persistence.LogisticsDataMapper;
import vip.fairy.commerceflow.simulator.logistics.infrastructure.persistence.row.ShipmentRow;

@Service
public class TestLogisticsCallbacks {

  public static final String PATH = "/internal/test-logistics-callbacks";
  public static final String PREFIX = "TEST-";

  private final LogisticsDataMapper dataMapper;
  private final String callbackBase;

  public TestLogisticsCallbacks(LogisticsDataMapper dataMapper, @Value("${server.port:8080}") int port) {
    this.dataMapper = dataMapper;
    this.callbackBase = "http://127.0.0.1:" + port + PATH;
  }

  public String callbackUrl(Result result) {
    return callbackBase + (result == Result.REJECT ? "/failure" : "/success");
  }

  public Result current(Long shipmentId) {
    return resultOf(dataMapper.shipmentById(shipmentId));
  }

  public Result forDelivery(String merchantNo, String platformNo) {
    ShipmentRow shipment = dataMapper.findShipmentByMerchantNo(merchantNo);
    Result result = resultOf(shipment);
    if (result == null || !shipment.platformShipmentNo().equals(platformNo)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "TEST_SHIPMENT_NOT_FOUND");
    }
    return result;
  }

  @Transactional
  public Result update(Long shipmentId, Result result) {
    Result current = resultOf(dataMapper.lockShipmentById(shipmentId));
    if (current == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "TEST_SHIPMENT_NOT_FOUND");
    }
    if (current != result) {
      dataMapper.updateTestCallbackResult(shipmentId, result.name());
    }
    return result;
  }

  private Result resultOf(ShipmentRow shipment) {
    if (shipment == null || !shipment.merchantShipmentNo().startsWith(PREFIX)
        || !shipment.merchantShipmentNo().equals(shipment.orderNumber())) {
      return null;
    }
    for (Result initial : Result.values()) {
      if (callbackUrl(initial).equals(shipment.callbackUrl())) {
        return shipment.testCallbackResult() == null ? initial : Result.valueOf(shipment.testCallbackResult());
      }
    }
    return null;
  }

  public enum Result {
    ACCEPT, REJECT
  }
}
