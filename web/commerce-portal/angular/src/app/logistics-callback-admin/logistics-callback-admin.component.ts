import { Component, OnInit } from '@angular/core';
import { ApiService } from '../services/api.service';
import { LogisticsCallbackAuditEvent, LogisticsCallbackOutbox } from 'src/model/ILogisticsCallbackAdmin';

@Component({
  standalone: false,
  selector: 'app-logistics-callback-admin',
  templateUrl: './logistics-callback-admin.component.html',
  styleUrls: ['./logistics-callback-admin.component.css'],
})
export class LogisticsCallbackAdminComponent implements OnInit {
  callbacks: LogisticsCallbackOutbox[] = [];
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

  load(): void {
    this.error = '';
    this.api
      .logisticsCallbacks(this.statusFilter)
      .subscribe({ next: (v) => (this.callbacks = v), error: (e) => this.fail(e) });
  }

  toggleEvents(item: LogisticsCallbackOutbox): void {
    if (this.events[item.id]) {
      delete this.events[item.id];
      return;
    }
    this.api.logisticsCallbackEvents(item.id).subscribe({
      next: (v) => (this.events[item.id] = v),
      error: (e) => this.fail(e),
    });
  }

  replay(item: LogisticsCallbackOutbox): void {
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
