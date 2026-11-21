package vip.fairy.commerceflow.navigation;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
class NavigationCatalog {

  private final JsonNode catalog;

  NavigationCatalog(ObjectMapper mapper) throws IOException {
    try (var input = new ClassPathResource("navigation-catalog.json").getInputStream()) {
      catalog = mapper.readTree(input);
    }
  }

  List<Entry> entries(JsonNode configuration) {
    List<Entry> entries = new ArrayList<>();
    for (Map.Entry<String, JsonNode> item : configuration.path("services").properties()) {
      String id = item.getKey();
      JsonNode service = item.getValue();
      JsonNode metadata = catalog.path(id);
      List<String> profiles = new ArrayList<>();
      service.path("profiles").forEach(profile -> profiles.add(profile.asString()));

      JsonNode configHealth = service.path("healthcheck");
      boolean healthcheck = !configHealth.path("disable").asBoolean(false)
          && configHealth.hasNonNull("test")
          && !"NONE".equals(configHealth.path("test").path(0).asString(""));
      boolean task = "task".equals(metadata.path("kind").asString(""));
      entries.add(new Entry(id, metadata.path("name").asString(id), metadata.path("group").asString("services"),
          task, healthcheck, List.copyOf(profiles), service.path("environment").path("MYSQL_DATABASE").asString(""),
          service.path("ports"), metadata));
    }
    return entries;
  }

  record Entry(String id, String name, String group, boolean task, boolean healthcheck,
               List<String> profiles, String schema, JsonNode ports, JsonNode metadata) {
  }
}
