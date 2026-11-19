package vip.fairy.commerceflow.notification.infrastructure.messaging;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.ListOffsetsResult.ListOffsetsResultInfo;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.GroupIdNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class NotificationConsumerLagMetrics {

  private static final Logger LOG = LoggerFactory.getLogger(NotificationConsumerLagMetrics.class);
  private static final long QUERY_TIMEOUT_SECONDS = 2;

  private final Admin admin;
  private final String topic;
  private final String group;
  private volatile long lag;
  private volatile boolean refreshed;

  public NotificationConsumerLagMetrics(KafkaAdmin kafkaAdmin, MeterRegistry registry,
                                        @Value("${commerceflow.notification.topic:customer-notification-commands}")
                                        String topic,
                                        @Value("${commerceflow.notification.consumer-group:"
                                          + "commerceflow-independent-notification-v1}") String group) {
    this.admin = Admin.create(kafkaAdmin.getConfigurationProperties());
    this.topic = topic;
    this.group = group;
    Gauge.builder("commerceflow.notification.consumer.lag", this, metrics -> metrics.lag)
      .description("Unconsumed Kafka offsets across the notification command topic for the configured consumer group")
      .register(registry);
    Gauge.builder("commerceflow.notification.consumer.metrics.refresh.success",
        this, metrics -> metrics.refreshed ? 1 : 0)
      .description("Whether the most recent broker-side consumer lag query succeeded")
      .register(registry);
  }

  @Scheduled(initialDelayString = "${commerceflow.notification.metrics-delay:5s}",
    fixedDelayString = "${commerceflow.notification.metrics-delay:5s}")
  public void refresh() {
    try {
      lag = readLag();
      refreshed = true;
    } catch (InterruptedException interrupted) {
      refreshed = false;
      Thread.currentThread().interrupt();
    } catch (ExecutionException | TimeoutException | RuntimeException failure) {
      refreshed = false;
      LOG.warn("Could not refresh notification consumer lag: {}", failure.getMessage());
    }
  }

  @PreDestroy
  void close() {
    admin.close(Duration.ofSeconds(1));
  }

  private long readLag() throws ExecutionException, InterruptedException, TimeoutException {
    var description = admin.describeTopics(List.of(topic)).allTopicNames()
      .get(QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS).get(topic);
    List<TopicPartition> partitions = description.partitions().stream()
      .map(partition -> new TopicPartition(topic, partition.partition())).toList();
    var earliest = admin.listOffsets(offsets(partitions, OffsetSpec.earliest())).all();
    var latest = admin.listOffsets(offsets(partitions, OffsetSpec.latest())).all();
    var committed = committedOffsets();
    Map<TopicPartition, ListOffsetsResultInfo> starts = earliest.get(QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    Map<TopicPartition, ListOffsetsResultInfo> ends = latest.get(QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    long total = 0;
    for (TopicPartition partition : partitions) {
      long start = starts.get(partition).offset();
      OffsetAndMetadata checkpoint = committed.get(partition);
      long position = checkpoint == null ? start : Math.max(start, checkpoint.offset());
      total += Math.max(0, ends.get(partition).offset() - position);
    }
    return total;
  }

  private Map<TopicPartition, OffsetAndMetadata> committedOffsets()
      throws ExecutionException, InterruptedException, TimeoutException {
    try {
      return admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata()
        .get(QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (ExecutionException failure) {
      if (failure.getCause() instanceof GroupIdNotFoundException) {
        return Map.of();
      }
      throw failure;
    }
  }

  private Map<TopicPartition, OffsetSpec> offsets(List<TopicPartition> partitions, OffsetSpec spec) {
    Map<TopicPartition, OffsetSpec> result = new HashMap<>();
    partitions.forEach(partition -> result.put(partition, spec));
    return result;
  }
}
