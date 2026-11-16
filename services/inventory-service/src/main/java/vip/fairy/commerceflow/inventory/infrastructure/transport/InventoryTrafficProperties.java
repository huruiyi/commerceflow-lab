package vip.fairy.commerceflow.inventory.infrastructure.transport;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "commerceflow.inventory")
public record InventoryTrafficProperties(boolean acceptingTraffic) {
}
