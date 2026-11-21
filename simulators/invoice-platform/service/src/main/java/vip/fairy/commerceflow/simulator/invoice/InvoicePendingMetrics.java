package vip.fairy.commerceflow.simulator.invoice;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import vip.fairy.commerceflow.simulator.invoice.InvoiceLedger.Kind;

@Component
class InvoicePendingMetrics {
  InvoicePendingMetrics(InvoiceMapper mapper, MeterRegistry registry) {
    for (Kind kind : Kind.values()) {
      Gauge.builder("commerceflow.invoice.pending.oldest.seconds", mapper, source -> source.oldestAcceptedSeconds(kind))
        .description("Age in seconds of the oldest pending simulator invoice request or receipt")
        .tags("kind", kind.name(), "phase", "processing")
        .register(registry);
      Gauge.builder("commerceflow.invoice.pending.oldest.seconds", mapper, source -> source.oldestUndeliveredReceiptSeconds(kind))
        .description("Age in seconds of the oldest pending simulator invoice request or receipt")
        .tags("kind", kind.name(), "phase", "receipt")
        .register(registry);
    }
  }
}
