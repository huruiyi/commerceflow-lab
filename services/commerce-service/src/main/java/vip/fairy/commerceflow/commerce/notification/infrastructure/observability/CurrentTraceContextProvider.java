package vip.fairy.commerceflow.commerce.notification.infrastructure.observability;

import java.util.UUID;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import vip.fairy.commerceflow.commerce.notification.application.TraceContextProvider;

import org.springframework.stereotype.Component;

@Component
public class CurrentTraceContextProvider implements TraceContextProvider {

  private final Tracer tracer;

  public CurrentTraceContextProvider(Tracer tracer) {
    this.tracer = tracer;
  }

  @Override
  public String traceparent() {
    Span span = tracer.currentSpan();
    if (span == null) {
      return randomTraceparent();
    }
    TraceContext context = span.context();
    String flags = Boolean.TRUE.equals(context.sampled()) ? "01" : "00";
    return "00-" + context.traceId() + "-" + context.spanId() + "-" + flags;
  }

  private String randomTraceparent() {
    String traceId = UUID.randomUUID().toString().replace("-", "");
    String spanId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    return "00-" + traceId + "-" + spanId + "-01";
  }
}
