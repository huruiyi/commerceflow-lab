import { Component, DestroyRef, inject, OnInit, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { finalize } from 'rxjs';
import { IUser } from 'src/model/IUser';
import { AdminListPage } from 'src/model/IAdminListPage';
import { ApiService } from '../services/api.service';
import { I18nService } from '../services/i18n.service';
import { SeoService } from '../services/seo.service';

@Component({
  standalone: false,
  selector: 'app-users',
  templateUrl: './users.component.html',
})
export class UsersComponent implements OnInit {
  private readonly api = inject(ApiService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly seo = inject(SeoService);
  readonly i18n = inject(I18nService);
  readonly result = signal<AdminListPage<IUser> | null>(null);
  readonly loading = signal(false);
  readonly failed = signal(false);
  query = '';
  private appliedQuery = '';

  ngOnInit(): void {
    this.seo.setTitle(this.i18n.t('userList'));
    this.load();
  }

  get totalPages(): number {
    const result = this.result();
    return result ? Math.ceil(result.total / result.size) : 0;
  }

  applyFilter(): void {
    if (this.loading()) return;
    this.appliedQuery = this.query.trim();
    this.load(0);
  }

  clearFilter(): void {
    this.query = '';
    this.applyFilter();
  }

  load(page = this.result()?.page ?? 0): void {
    if (this.loading()) return;
    this.loading.set(true);
    this.failed.set(false);
    this.api
      .getUsers(this.appliedQuery, page)
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.loading.set(false)),
      )
      .subscribe({
        next: (value) => this.result.set(value),
        error: () => this.failed.set(true),
      });
  }
}
