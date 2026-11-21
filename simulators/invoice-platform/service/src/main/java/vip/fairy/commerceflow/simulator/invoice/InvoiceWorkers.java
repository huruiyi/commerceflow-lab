package vip.fairy.commerceflow.simulator.invoice;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.simulator.invoice.InvoiceLedger.Receipt;
import vip.fairy.commerceflow.simulator.invoice.InvoiceLedger.Request;
import vip.fairy.commerceflow.simulator.invoice.InvoiceLedger.Result;
import vip.fairy.commerceflow.simulator.invoice.InvoiceSimulatorApplication.Settings;

@Component
class InvoiceWorkers {
  private static final Logger log = LoggerFactory.getLogger(InvoiceWorkers.class);
  private final InvoiceMapper mapper;
  private final InvoiceService service;
  private final Settings settings;
  private final ObjectMapper json;
  private final RestClient client;

  InvoiceWorkers(InvoiceMapper mapper, InvoiceService service, Settings settings, ObjectMapper json) {
    this.mapper = mapper;
    this.service = service;
    this.settings = settings;
    this.json = json;
    var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    var factory = new JdkClientHttpRequestFactory(http);
    factory.setReadTimeout(Duration.ofSeconds(2));
    this.client = RestClient.builder().requestFactory(factory).build();
  }

  @Bean
  static ThreadPoolTaskScheduler invoiceTaskScheduler() {
    var scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(2);
    scheduler.setThreadNamePrefix("invoice-worker-");
    return scheduler;
  }

  @Scheduled(fixedDelay = 250)
  void process() {
    for (Request candidate : mapper.findDueRequests()) {
      try {
        service.complete(candidate);
      } catch (RuntimeException ex) {
        log.error("Unable to complete invoice request {}", candidate.requestId(), ex);
      }
    }
  }

  @Scheduled(fixedDelay = 250)
  void deliver() {
    for (Receipt receipt : mapper.findDueReceipts()) {
      try {
        if (send(receipt)) {
          mapper.acknowledgeReceipt(receipt.eventId());
        } else {
          mapper.deferReceipt(receipt.eventId(), settings.callbackRetryMs());
        }
      } catch (RuntimeException ex) {
        log.warn("Invoice receipt {} will be retried", receipt.eventId());
        mapper.deferReceipt(receipt.eventId(), settings.callbackRetryMs());
      }
    }
  }

  private boolean send(Receipt receipt) {
    byte[] body = json.writeValueAsBytes(new Event(receipt.eventId(), service.request(receipt.requestId())));
    String timestamp = Long.toString(Instant.now().getEpochSecond());
    String signature = InvoiceSignature.sign(settings.callbackSecret(), timestamp, "POST",
      settings.callbackUrl().getRawPath(), body);
    return client.post().uri(settings.callbackUrl())
      .contentType(MediaType.APPLICATION_JSON)
      .header(InvoiceSignature.TIMESTAMP_HEADER, timestamp)
      .header(InvoiceSignature.SIGNATURE_HEADER, signature)
      .body(body)
      .exchange((_, response) -> response.getStatusCode().is2xxSuccessful());
  }

  private record Event(String eventId, Result result) {
  }
}
