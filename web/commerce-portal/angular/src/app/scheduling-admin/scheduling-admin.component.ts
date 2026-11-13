import { Component, OnInit } from '@angular/core';
import { ApiService } from '../services/api.service';
import { ScheduledTask, ScheduledTaskEvent, SchedulingPolicy, SchedulingPolicyEvent } from 'src/model/IScheduling';

@Component({
  standalone: false,
  selector: 'app-scheduling-admin',
  templateUrl: './scheduling-admin.component.html',
  styleUrls: ['./scheduling-admin.component.css'],
})
export class SchedulingAdminComponent implements OnInit {
  policies: SchedulingPolicy[] = [];
  tasks: ScheduledTask[] = [];
  events: Record<number, ScheduledTaskEvent[]> = {};
  policyEvents: Record<string, SchedulingPolicyEvent[]> = {};
  taskTypeFilter = '';
  statusFilter = '';
  newTaskType = '';
  busy = false;
  message = '';
  error = '';

  constructor(private readonly api: ApiService) {}

  ngOnInit(): void {
    this.reload();
  }

  reload(): void {
    this.error = '';
    this.api
      .schedulingPolicies()
      .subscribe({ next: (values) => (this.policies = values), error: (error) => this.fail(error) });
    this.loadTasks();
  }

  loadTasks(): void {
    this.api.scheduledTasks(this.taskTypeFilter, this.statusFilter).subscribe({
      next: (values) => (this.tasks = values),
      error: (error) => this.fail(error),
    });
  }

  addPolicy(): void {
    const taskType = this.newTaskType.trim().toUpperCase();
    if (!/^[A-Z][A-Z0-9_]{2,63}$/.test(taskType)) {
      this.error = 'Invalid task type';
      return;
    }
    const policy: SchedulingPolicy = {
      taskType,
      enabled: false,
      primaryTrigger: 'DATABASE',
      reconciliationEnabled: true,
      gracePeriodSeconds: 120,
      claimLeaseSeconds: 120,
      maxAttempts: 5,
      retryDelaySeconds: 30,
      version: 0,
      source: 'NEW',
      handlerAvailable: false,
    };
    this.save(policy);
    this.newTaskType = '';
  }

  save(policy: SchedulingPolicy): void {
    this.run(() =>
      this.api.saveSchedulingPolicy(policy).subscribe({
        next: (value) => {
          this.replacePolicy(value);
          this.success('schedulingPolicySaved');
        },
        error: (error) => this.fail(error),
      }),
    );
  }

  reset(policy: SchedulingPolicy): void {
    this.run(() =>
      this.api.resetSchedulingPolicy(policy.taskType).subscribe({
        next: (value) => {
          this.replacePolicy(value);
          this.success('schedulingPolicyReset');
        },
        error: (error) => this.fail(error),
      }),
    );
  }

  togglePolicyEvents(policy: SchedulingPolicy): void {
    if (this.policyEvents[policy.taskType]) {
      delete this.policyEvents[policy.taskType];
      return;
    }
    this.api.schedulingPolicyEvents(policy.taskType).subscribe({
      next: (values) => (this.policyEvents[policy.taskType] = values),
      error: (error) => this.fail(error),
    });
  }

  toggleEvents(task: ScheduledTask): void {
    if (this.events[task.id]) {
      delete this.events[task.id];
      return;
    }
    this.api.scheduledTaskEvents(task.id).subscribe({
      next: (values) => (this.events[task.id] = values),
      error: (error) => this.fail(error),
    });
  }

  retry(task: ScheduledTask): void {
    this.run(() =>
      this.api.retryScheduledTask(task.id).subscribe({
        next: (value) => {
          Object.assign(task, value);
          this.success('scheduledTaskRetried');
        },
        error: (error) => this.fail(error),
      }),
    );
  }

  cancel(task: ScheduledTask): void {
    this.run(() =>
      this.api.cancelScheduledTask(task.id, 'Cancelled from scheduling administration').subscribe({
        next: (value) => {
          Object.assign(task, value);
          this.success('scheduledTaskCancelled');
        },
        error: (error) => this.fail(error),
      }),
    );
  }

  private replacePolicy(value: SchedulingPolicy): void {
    const index = this.policies.findIndex((item) => item.taskType === value.taskType);
    if (index >= 0) this.policies[index] = value;
    else this.policies = [...this.policies, value].sort((a, b) => a.taskType.localeCompare(b.taskType));
  }

  private run(action: () => void): void {
    if (this.busy) return;
    this.busy = true;
    this.error = '';
    action();
  }

  private success(key: string): void {
    this.busy = false;
    this.message = key;
    this.error = '';
  }

  private fail(error: any): void {
    this.busy = false;
    this.error = error?.error?.message || 'Request failed';
  }
}
