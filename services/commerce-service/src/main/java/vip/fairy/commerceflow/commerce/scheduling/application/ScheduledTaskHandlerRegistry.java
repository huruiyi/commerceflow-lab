package vip.fairy.commerceflow.commerce.scheduling.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

@Component
public class ScheduledTaskHandlerRegistry {

  private final Map<String, ScheduledTaskHandler> handlers;

  public ScheduledTaskHandlerRegistry(List<ScheduledTaskHandler> discovered) {
    Map<String, ScheduledTaskHandler> mapped = new LinkedHashMap<>();
    for (ScheduledTaskHandler handler : discovered) {
      String type = ScheduledTaskTypes.normalize(handler.taskType());
      if (mapped.put(type, handler) != null) {
        throw new IllegalStateException("Duplicate scheduled task handler: " + type);
      }
    }
    this.handlers = Map.copyOf(mapped);
  }

  public Optional<ScheduledTaskHandler> find(String taskType) {
    return Optional.ofNullable(handlers.get(ScheduledTaskTypes.normalize(taskType)));
  }

  public Set<String> registeredTypes() {
    return handlers.keySet();
  }
}
