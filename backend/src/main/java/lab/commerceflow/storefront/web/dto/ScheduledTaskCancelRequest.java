package lab.commerceflow.storefront.web.dto;

import jakarta.validation.constraints.Size;

public record ScheduledTaskCancelRequest(@Size(max = 500) String reason) {
}
