import { DatePipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { finalize, Observable } from 'rxjs';

interface Report {
  delivery: { eventId: string; topic: string; status: string; attempts: number; lastError: string | null };
  actions: {
    requestId: string;
    actorId: number;
    reason: string;
    priorAttempts: number;
    priorError: string | null;
    createdAt: string;
  }[];
}

@Component({
  selector: 'app-notification-recovery',
  imports: [DatePipe, FormsModule],
  templateUrl: './notification-recovery.component.html',
})
export class NotificationRecoveryComponent {
  private readonly http = inject(HttpClient);
  private readonly destroy = inject(DestroyRef);
  readonly report = signal<Report | null>(null);
  readonly busy = signal(false);
  readonly error = signal('');
  eventId = '';
  reason = '';
  private draft?: { eventId: string; reason: string; requestId: string };

  lookup(): void {
    if (this.busy() || !this.eventId.trim()) return;
    this.report.set(null);
    this.run(this.http.get<Report>(this.path(this.eventId.trim())));
  }

  retry(): void {
    const delivery = this.report()?.delivery;
    const reason = this.reason.trim();
    if (this.busy() || delivery?.status !== 'DEAD' || !reason) return;
    const eventId = delivery.eventId;
    if (this.draft?.eventId !== eventId || this.draft?.reason !== reason) {
      this.draft = { eventId, reason, requestId: crypto.randomUUID() };
    }
    this.run(
      this.http.post<Report>(this.path(eventId) + '/retries', { requestId: this.draft.requestId, reason }),
      true,
    );
  }

  private path(eventId: string): string {
    return '/api/admin/notification-deliveries/' + encodeURIComponent(eventId);
  }

  private run(request: Observable<Report>, retried = false): void {
    this.error.set('');
    this.busy.set(true);
    request
      .pipe(
        takeUntilDestroyed(this.destroy),
        finalize(() => this.busy.set(false)),
      )
      .subscribe({
        next: (value) => {
          this.report.set(value);
          if (retried) {
            this.draft = undefined;
            this.reason = '';
          }
        },
        error: (error) =>
          this.error.set(error.error?.message || '暂时无法处理通知 / Notification operation unavailable'),
      });
  }
}
