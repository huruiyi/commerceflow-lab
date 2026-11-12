package lab.commerceflow.storefront.web;

import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lab.commerceflow.storefront.service.LogisticsGatewayClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/logistics-callbacks")
@PreAuthorize("hasRole('ADMIN')")
public class LogisticsCallbackAdminController {
  private final LogisticsGatewayClient logistics;

  public LogisticsCallbackAdminController(LogisticsGatewayClient logistics) {
    this.logistics = logistics;
  }

  @GetMapping
  List<LogisticsGatewayClient.CallbackOutbox> list(
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "100") int limit) {
    return logistics.callbackOutbox(status, limit);
  }

  @GetMapping("/{id}/events")
  List<LogisticsGatewayClient.CallbackAuditEvent> events(@PathVariable Long id) {
    return logistics.callbackEvents(id);
  }

  @PostMapping("/{id}/replay")
  LogisticsGatewayClient.CallbackOutbox replay(@PathVariable Long id,
      @Valid @RequestBody ReplayRequest request, Authentication authentication) {
    return logistics.replayCallback(id, authentication.getName(), request.reason());
  }

  public record ReplayRequest(@NotBlank String reason) {}
}
