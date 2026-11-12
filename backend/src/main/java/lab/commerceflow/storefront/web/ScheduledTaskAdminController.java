package lab.commerceflow.storefront.web;

import jakarta.validation.Valid;
import java.time.Duration;
import java.util.List;
import lab.commerceflow.storefront.domain.ScheduledTaskEventView;
import lab.commerceflow.storefront.domain.ScheduledTaskPolicyEventView;
import lab.commerceflow.storefront.domain.ScheduledTaskView;
import lab.commerceflow.storefront.scheduling.ScheduledTaskPolicyService;
import lab.commerceflow.storefront.scheduling.ScheduledTaskService;
import lab.commerceflow.storefront.web.dto.ScheduledTaskCancelRequest;
import lab.commerceflow.storefront.web.dto.ScheduledTaskPolicyRequest;
import lab.commerceflow.storefront.web.dto.ScheduledTaskPolicyResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/scheduling")
@PreAuthorize("hasRole('ADMIN')")
public class ScheduledTaskAdminController {
  private final ScheduledTaskPolicyService policies;
  private final ScheduledTaskService tasks;

  public ScheduledTaskAdminController(ScheduledTaskPolicyService policies, ScheduledTaskService tasks) {
    this.policies = policies;
    this.tasks = tasks;
  }

  @GetMapping("/policies")
  List<ScheduledTaskPolicyResponse> policies() {
    return policies.all().stream().map(ScheduledTaskPolicyResponse::from).toList();
  }

  @PutMapping("/policies/{taskType}")
  ScheduledTaskPolicyResponse savePolicy(@PathVariable String taskType,
                                         @Valid @RequestBody ScheduledTaskPolicyRequest request,
                                         Authentication authentication) {
    return ScheduledTaskPolicyResponse.from(policies.save(taskType, request.enabled(),
        request.primaryTrigger(), request.reconciliationEnabled(),
        Duration.ofSeconds(request.gracePeriodSeconds()),
        Duration.ofSeconds(request.claimLeaseSeconds()), request.maxAttempts(),
        Duration.ofSeconds(request.retryDelaySeconds()), authentication.getName()));
  }

  @DeleteMapping("/policies/{taskType}")
  ScheduledTaskPolicyResponse resetPolicy(@PathVariable String taskType,
                                          Authentication authentication) {
    return ScheduledTaskPolicyResponse.from(policies.reset(taskType, authentication.getName()));
  }

  @GetMapping("/policies/{taskType}/events")
  List<ScheduledTaskPolicyEventView> policyEvents(@PathVariable String taskType) {
    return policies.events(taskType);
  }

  @GetMapping("/tasks")
  List<ScheduledTaskView> tasks(@RequestParam(required = false) String taskType,
                                @RequestParam(required = false) String status,
                                @RequestParam(defaultValue = "100") int limit) {
    return tasks.list(taskType, status, limit);
  }

  @GetMapping("/tasks/{id}/events")
  List<ScheduledTaskEventView> taskEvents(@PathVariable Long id) {
    return tasks.events(id);
  }

  @PostMapping("/tasks/{id}/retry")
  ScheduledTaskView retry(@PathVariable Long id) {
    return tasks.retry(id);
  }

  @PostMapping("/tasks/{id}/cancel")
  ScheduledTaskView cancel(@PathVariable Long id,
                           @Valid @RequestBody(required = false) ScheduledTaskCancelRequest request) {
    return tasks.cancel(id, request == null ? null : request.reason());
  }
}
