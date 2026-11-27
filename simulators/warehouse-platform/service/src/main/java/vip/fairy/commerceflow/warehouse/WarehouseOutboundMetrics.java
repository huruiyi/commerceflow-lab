package vip.fairy.commerceflow.warehouse;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.function.ToLongFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vip.fairy.commerceflow.warehouse.WarehouseOutboundMapper.OutboundCounts;

@Component
class WarehouseOutboundMetrics {

  private static final Logger LOGGER = LoggerFactory.getLogger(WarehouseOutboundMetrics.class);

  private final WarehouseOutboundMapper outbounds;
  private volatile OutboundCounts counts;

  WarehouseOutboundMetrics(WarehouseOutboundMapper outbounds, MeterRegistry registry) {
    this.outbounds = outbounds;
    register(registry, "PENDING", OutboundCounts::pending);
    register(registry, "DISPATCHED", OutboundCounts::dispatched);
    register(registry, "VOIDED", OutboundCounts::voided);
    Gauge.builder("commerceflow.warehouse.outbounds.metrics.refresh.success", this,
        metrics -> metrics.counts == null ? 0 : 1)
      .register(registry);
  }

  @Scheduled(fixedDelay = 10_000)
  void refresh() {
    try {
      counts = outbounds.outboundCounts();
    } catch (RuntimeException failure) {
      counts = null;
      LOGGER.warn("Could not refresh warehouse outbound counts", failure);
    }
  }

  private void register(MeterRegistry registry, String status, ToLongFunction<OutboundCounts> extract) {
    Gauge.builder("commerceflow.warehouse.outbounds", this, metrics -> {
      OutboundCounts snapshot = metrics.counts;
      return snapshot == null ? Double.NaN : extract.applyAsLong(snapshot);
    }).tag("status", status).register(registry);
  }
}
