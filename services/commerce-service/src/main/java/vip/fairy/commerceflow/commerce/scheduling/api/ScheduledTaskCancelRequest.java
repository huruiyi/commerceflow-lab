package vip.fairy.commerceflow.commerce.scheduling.api;

import jakarta.validation.constraints.Size;

public record ScheduledTaskCancelRequest(@Size(max = 500) String reason) {
}
