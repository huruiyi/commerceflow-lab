import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { finalize, forkJoin, Subscription } from 'rxjs';
import { readPage } from '../list-pagination/page-query';
import { I18nService } from '../services/i18n.service';

interface LedgerSummary {
  createdCount: number;
  paidCount: number;
  receivedAmount: string;
  refundCount: number;
  refundedAmount: string;
  netAmount: string;
  currentPaymentStatuses: Record<string, number>;
  missingPaidAtCount: number;
  missingRefundSucceededAtCount: number;
}

interface CurrencySummary {
  currency: string;
  summary: LedgerSummary;
}

interface ChannelSummary extends CurrencySummary {
  channel: string;
}

interface DailySummary extends ChannelSummary {
  date: string;
}

interface LedgerReport {
  startDate: string;
  endDate: string;
  timeZone: string;
  daily: DailySummary[];
  channels: ChannelSummary[];
  totals: CurrencySummary[];
  unattributed: CurrencySummary[];
}

interface MerchantOption {
  merchantId: number;
  name: string;
}

const DAY = 86_400_000;
const DEFAULT_ZONE = 'Asia/Shanghai';
const CHANNELS = ['ALIPAY', 'WECHAT_PAY', 'UNION_PAY'] as const;
const DAILY_PAGE_SIZE = 12;

function dateAtZone(date: Date, timeZone: string): string {
  const parts = new Intl.DateTimeFormat('en', {
    timeZone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(date);
  const read = (type: string) => parts.find((part) => part.type === type)?.value;
  return [read('year'), read('month'), read('day')].join('-');
}

function utcDay(date: string): number {
  return Date.parse(date + 'T00:00:00Z');
}

function dateLabel(instant: number): string {
  return new Date(instant).toISOString().slice(0, 10);
}

function initialFilters() {
  const endDate = dateAtZone(new Date(), DEFAULT_ZONE);
  return {
    startDate: dateLabel(utcDay(endDate) - 6 * DAY),
    endDate,
    timeZone: DEFAULT_ZONE,
    channel: '',
    currency: '',
    merchantId: '',
  };
}

type LedgerFilters = ReturnType<typeof initialFilters>;

@Component({
  selector: 'app-payment-ledger-reports',
  standalone: false,
  templateUrl: './payment-ledger-reports.component.html',
  styleUrl: './payment-ledger-reports.component.css',
})
export class PaymentLedgerReportsComponent {
  private readonly http = inject(HttpClient);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  private readonly api = this.route.snapshot.data['admin']
    ? '/api/admin/payment-ledger-reports'
    : '/api/merchant/payment-ledger-reports';
  private readonly defaultFilters = initialFilters();
  private readonly requestedPage = signal(0);
  private readonly requestedCurrency = signal('');
  private appliedFilters?: LedgerFilters;
  private reportRequest?: Subscription;

  readonly i18n = inject(I18nService);
  readonly timeZones = [...new Set([DEFAULT_ZONE, 'UTC', 'America/New_York', ...Intl.supportedValuesOf('timeZone')])];
  readonly channels = CHANNELS;
  readonly currencies = [...new Set(['CNY', 'USD', 'EUR', 'JPY', 'HKD', ...Intl.supportedValuesOf('currency')])];
  readonly merchants = signal<MerchantOption[]>([]);
  readonly report = signal<LedgerReport | null>(null);
  readonly loading = signal(false);
  readonly error = signal('');
  readonly viewCurrency = computed(() => {
    const totals = this.report()?.totals;
    const requested = this.requestedCurrency();
    return totals?.find((row) => row.currency === requested)?.currency ?? totals?.[0]?.currency ?? '';
  });
  readonly selected = computed(() => this.report()?.totals.find((row) => row.currency === this.viewCurrency()));
  readonly daily = computed(() => this.report()?.daily.filter((row) => row.currency === this.viewCurrency()) ?? []);
  readonly dailyTotalPages = computed(() => Math.ceil(this.daily().length / DAILY_PAGE_SIZE));
  readonly dailyPage = computed(() => Math.min(this.requestedPage(), Math.max(0, this.dailyTotalPages() - 1)));
  readonly dailyPageRows = computed(() => {
    const firstRow = this.dailyPage() * DAILY_PAGE_SIZE;
    return this.daily().slice(firstRow, firstRow + DAILY_PAGE_SIZE);
  });
  readonly byChannel = computed(
    () => this.report()?.channels.filter((row) => row.currency === this.viewCurrency()) ?? [],
  );
  readonly unattributed = computed(() =>
    this.report()?.unattributed.find((row) => row.currency === this.viewCurrency()),
  );
  readonly statuses = computed(() =>
    Object.entries(this.selected()?.summary.currentPaymentStatuses ?? {}).sort(
      (a, b) => b[1] - a[1] || a[0].localeCompare(b[0]),
    ),
  );
  readonly trend = computed(() => {
    const report = this.report();
    if (!report || !this.daily().length) return [];
    const buckets = new Map<string, { created: number; paid: number }>();
    for (const row of this.daily()) {
      const counts = buckets.get(row.date) ?? { created: 0, paid: 0 };
      counts.created += row.summary.createdCount;
      counts.paid += row.summary.paidCount;
      buckets.set(row.date, counts);
    }
    const rows = [];
    for (let day = utcDay(report.startDate); day <= utcDay(report.endDate); day += DAY) {
      const date = dateLabel(day);
      rows.push({ date, ...(buckets.get(date) ?? { created: 0, paid: 0 }) });
    }
    return rows;
  });
  readonly trendPeak = computed(() => Math.max(1, ...this.trend().map((day) => Math.max(day.created, day.paid))));
  filters = { ...this.defaultFilters };

  constructor() {
    this.route.queryParamMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((params) => {
      this.requestedPage.set(readPage(params.get('page')));
      this.requestedCurrency.set(params.get('viewCurrency') ?? '');
      const filters = {
        startDate: params.get('startDate') ?? this.defaultFilters.startDate,
        endDate: params.get('endDate') ?? this.defaultFilters.endDate,
        timeZone: params.get('timeZone') ?? this.defaultFilters.timeZone,
        channel: params.get('channel') ?? '',
        currency: params.get('currency') ?? '',
        merchantId: params.get('merchantId') ?? '',
      };
      if (this.sameFilters(filters)) return;
      this.appliedFilters = filters;
      this.filters = { ...filters };
      this.loadReport(filters);
    });
  }

  reset(): void {
    this.filters = { ...this.defaultFilters };
    this.search();
  }

  search(): void {
    if (this.loading()) return;
    const filters = { ...this.filters };
    const currency = this.viewCurrency();
    if (this.sameFilters(filters)) this.loadReport(filters);
    this.navigateReport(filters, currency, 0);
  }

  changeDailyPage(page: number): void {
    if (this.appliedFilters) this.navigateReport(this.appliedFilters, this.viewCurrency(), page);
  }

  changeViewCurrency(currency: string): void {
    if (this.appliedFilters && currency !== this.viewCurrency()) this.navigateReport(this.appliedFilters, currency, 0);
  }

  private sameFilters(filters: LedgerFilters): boolean {
    return JSON.stringify(filters) === JSON.stringify(this.appliedFilters);
  }

  private navigateReport(filters: LedgerFilters, viewCurrency: string, page: number): void {
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: {
        ...filters,
        channel: filters.channel || null,
        currency: filters.currency || null,
        merchantId: filters.merchantId || null,
        viewCurrency: viewCurrency || null,
        page: page || null,
      },
    });
  }

  private loadReport(filters: LedgerFilters): void {
    this.reportRequest?.unsubscribe();
    const { startDate, endDate, timeZone, channel, currency, merchantId } = filters;
    const span = (utcDay(endDate) - utcDay(startDate)) / DAY;
    if (!Number.isFinite(span) || span < 0 || span >= 366) {
      this.error.set('ledgerInvalidDates');
      this.report.set(null);
      return;
    }
    let params = new HttpParams().set('startDate', startDate).set('endDate', endDate).set('timeZone', timeZone);
    if (channel) params = params.set('channel', channel);
    if (currency) params = params.set('currency', currency);
    if (merchantId) params = params.set('merchantId', merchantId);
    this.error.set('');
    this.loading.set(true);
    this.report.set(null);
    this.reportRequest = forkJoin({
      merchants: this.http.get<MerchantOption[]>(this.api + '/merchants'),
      report: this.http.get<LedgerReport>(this.api, { params }),
    })
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.loading.set(false)),
      )
      .subscribe({
        next: ({ merchants, report }) => {
          this.merchants.set(merchants);
          this.report.set(report);
        },
        error: (error: HttpErrorResponse) => {
          if (error.status === 401 || error.status === 403) {
            this.merchants.set([]);
            this.filters.merchantId = '';
            this.error.set('ledgerAccessDenied');
          } else {
            this.error.set('ledgerLoadFailed');
          }
        },
      });
  }

  amount(value: string, currency: string): string {
    const negative = value.startsWith('-');
    const [whole, cents = '00'] = (negative ? value.slice(1) : value).split('.');
    const grouped = new Intl.NumberFormat(this.i18n.locale()).format(BigInt(whole));
    return (negative ? '−' : '') + grouped + '.' + cents + ' ' + currency;
  }

  statusLabel(status: string): string {
    const key = 'ledgerStatus_' + status;
    const label = this.i18n.t(key);
    return label === key ? status : label;
  }
}
