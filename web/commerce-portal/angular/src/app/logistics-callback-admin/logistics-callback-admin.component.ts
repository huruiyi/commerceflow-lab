import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { finalize } from 'rxjs';
import { AdminListPage } from 'src/model/IAdminListPage';
import { I18nService } from '../services/i18n.service';
import { ChangeDetectorRef, Component, DestroyRef, inject, OnInit, signal } from '@angular/core';
import { ApiService } from '../services/api.service';
import { LogisticsCallbackAuditEvent, LogisticsCallbackOutbox } from 'src/model/ILogisticsCallbackAdmin';

@Component({
  standalone: false,
  selector: 'app-logistics-callback-admin',
  templateUrl: './logistics-callback-admin.component.html',
  styleUrls: ['./logistics-callback-admin.component.css'],
})
export class LogisticsCallbackAdminComponent implements OnInit {
  callbackPage?: AdminListPage<LogisticsCallbackOutbox>;
  readonly i18n = inject(I18nService);
  readonly loading = signal(false);
  private readonly destroyRef = inject(DestroyRef);
  private readonly changes = inject(ChangeDetectorRef);
  private appliedStatus = '';
  events: Record<number, LogisticsCallbackAuditEvent[]> = {};
  replayReasons: Record<number, string> = {};
  statusFilter = '';
  busy = false;
  error = '';
  message = '';

  constructor(private readonly api: ApiService) {}

  ngOnInit(): void {
    this.load();
  }

  get callbacks(): LogisticsCallbackOutbox[] {
    return this.callbackPage?.rows ?? [];
  }

  get totalPages(): number {
    return this.callbackPage ? Math.ceil(this.callbackPage.total / this.callbackPage.size) : 0;
  }

  applyFilter(): void {
    if (this.loading()) return;
    this.appliedStatus = this.statusFilter;
    this.load(0);
  }

  clearFilter(): void {
    this.statusFilter = '';
    this.applyFilter();
  }

  load(page = this.callbackPage?.page ?? 0): void {
    if (this.loading()) return;
    this.error = '';
    this.loading.set(true);
    this.api
      .logisticsCallbacks(this.appliedStatus, page)
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.loading.set(false)),
      )
      .subscribe({
        next: (value) => (this.callbackPage = value),
        error: (error) => this.fail(error),
      });
  }

  toggleEvents(item: LogisticsCallbackOutbox): void {
    if (this.events[item.id]) {
      delete this.events[item.id];
      return;
    }
    this.api.logisticsCallbackEvents(item.id).subscribe({
      next: (v) => {
        this.events[item.id] = v;
        this.changes.markForCheck();
      },
      error: (e) => this.fail(e),
    });
  }

  replay(item: LogisticsCallbackOutbox): void {
    if (this.busy || this.loading()) return;
    const reason = (this.replayReasons[item.id] || '').trim();
    if (!reason) {
      this.error = 'replayReasonRequired';
      return;
    }
    this.busy = true;
    this.error = '';
    this.api.replayLogisticsCallback(item.id, reason).subscribe({
      next: (v) => {
        Object.assign(item, v);
        this.message = 'logisticsCallbackReplayQueued';
        this.busy = false;
        this.load();
      },
      error: (e) => this.fail(e),
    });
  }

  private fail(error: any): void {
    this.busy = false;
    this.error = error?.error?.message || 'requestFailed';
  }
}
