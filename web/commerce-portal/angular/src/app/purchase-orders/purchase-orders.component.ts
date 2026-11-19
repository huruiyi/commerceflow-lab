import { afterNextRender, ChangeDetectorRef, Component, DestroyRef, ElementRef, Injector, OnInit } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, NavigationSkipped, NavigationSkippedCode, ParamMap, Router } from '@angular/router';
import {
  catchError,
  distinctUntilChanged,
  exhaustMap,
  filter,
  finalize,
  forkJoin,
  map,
  Observable,
  of,
  switchMap,
  take,
  timeout,
  TimeoutError,
  timer,
} from 'rxjs';
import {
  initialOrderSearch,
  OrderAction,
  orderDetailSections,
  OrderEvent,
  OrderSearchCriteria,
  OrderSearchPage,
  orderSearchStatuses,
  PaymentChannel,
  PurchaseOrder,
  RepurchaseResult,
} from 'src/model/IPurchaseOrder';
import { ApiService } from '../services/api.service';
import { I18nService } from '../services/i18n.service';

interface ChannelOption {
  code: PaymentChannel;
  nameKey: string;
  merchantNo: string;
  appId: string;
  protocol: string;
  descriptionKey: string;
  badge: string;
}

interface PaymentAttempt {
  key: string;
  channel: PaymentChannel;
  simulateFailure: boolean;
  paymentNumber?: string;
}

interface Notice {
  type: 'success' | 'warning' | 'danger' | 'info';
  text: string;
}

@Component({
  standalone: false,
  selector: 'app-purchase-orders',
  templateUrl: './purchase-orders.component.html',
  styleUrls: ['./purchase-orders.component.css'],
})
export class PurchaseOrdersComponent implements OnInit {
  readonly reviewScores = [1, 2, 3, 4, 5];
  readonly channels: ChannelOption[] = [
    {
      code: 'ALIPAY',
      nameKey: 'alipay',
      merchantNo: 'ALIPAY_MERCHANT_10001',
      appId: '2026111000000001',
      protocol: 'RSA-SHA256',
      descriptionKey: 'alipayDescription',
      badge: 'A',
    },
    {
      code: 'WECHAT_PAY',
      nameKey: 'wechatPay',
      merchantNo: '1900001001',
      appId: 'wx202611100001',
      protocol: 'HMAC-SHA256',
      descriptionKey: 'wechatDescription',
      badge: 'W',
    },
    {
      code: 'UNION_PAY',
      nameKey: 'unionPay',
      merchantNo: '898310000000001',
      appId: 'UP202611100001',
      protocol: 'RSA-SHA256',
      descriptionKey: 'unionPayDescription',
      badge: 'U',
    },
  ];
  orders: PurchaseOrder[] = [];
  criteria = { ...initialOrderSearch };
  page?: OrderSearchPage;
  paying = new Set<number>();
  refreshingPayment = new Set<number>();
  selectedChannels: Record<number, PaymentChannel> = {};
  draftKeys: Record<number, string> = {};
  lastAttempts: Record<number, PaymentAttempt> = {};
  notices: Record<number, Notice> = {};
  ratings: Record<number, number> = {};
  reviewComments: Record<number, string> = {};
  reviewed = new Set<number>();
  reviewing = new Set<number>();
  refundForms = new Set<number>();
  refunding = new Set<number>();
  refundReasons: Record<number, string> = {};
  refundDetails: Record<number, string> = {};
  refundKeys: Record<number, string> = {};
  cancelForms = new Set<number>();
  cancelling = new Set<number>();
  receiving = new Set<number>();
  cancellationReasons: Record<number, string> = {};
  unavailableProductImages = new Set<number>();
  expandedOrders = new Set<number>();
  repurchasing = new Set<number>();
  repurchaseResults: Record<number, RepurchaseResult> = {};
  loading = true;
  loadError = '';
  detailId: string | null = null;
  detailUnavailable = false;
  private loadVersion = 0;
  private expansionInitialized = false;

  constructor(
    private api: ApiService,
    private changeDetector: ChangeDetectorRef,
    public i18n: I18nService,
    private destroyRef: DestroyRef,
    private route: ActivatedRoute,
    private router: Router,
    private element: ElementRef<HTMLElement>,
    private injector: Injector,
  ) {}

  ngOnInit(): void {
    if (this.route.snapshot.paramMap.has('id')) {
      this.route.queryParamMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((params) => {
        this.criteria = this.readSearch(params);
      });
      this.route.paramMap
        .pipe(
          map((params) => params.get('id')),
          distinctUntilChanged(),
          takeUntilDestroyed(this.destroyRef),
        )
        .subscribe((id) => {
          this.detailId = id;
          this.orders = [];
          this.reload();
        });
      this.route.fragment.pipe(distinctUntilChanged(), takeUntilDestroyed(this.destroyRef)).subscribe(() => {
        if (!this.loading && this.orders.length) this.focusOrderDetail();
      });
      this.router.events.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((event) => {
        if (
          event instanceof NavigationSkipped &&
          event.code === NavigationSkippedCode.IgnoredSameUrlNavigation &&
          event.url === this.router.url &&
          !this.loading &&
          this.orders.length
        ) {
          this.focusOrderDetail();
        }
      });
    } else {
      this.route.queryParamMap
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe((params) => this.reload(this.readSearch(params)));
    }
    this.i18n.localeChanged.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => this.reload());
  }

  get listQueryParams() {
    return this.searchParams(this.criteria);
  }

  applyCriteria(criteria: OrderSearchCriteria): void {
    void this.router.navigate([], { relativeTo: this.route, queryParams: this.searchParams(criteria) });
  }

  private readSearch(params: ParamMap): OrderSearchCriteria {
    const status = params.get('status') ?? '';
    const page = Number(params.get('page') ?? 0);
    const from = params.get('from') ?? '';
    const to = params.get('to') ?? '';
    const date = /^\d{4}-\d{2}-\d{2}$/;
    return {
      q: (params.get('q') ?? '').trim().slice(0, 120),
      status: orderSearchStatuses.includes(status) ? status : '',
      from: date.test(from) ? from : '',
      to: date.test(to) && (!date.test(from) || from <= to) ? to : '',
      page: Number.isInteger(page) && page >= 0 && page <= 2147483647 ? page : 0,
    };
  }

  private searchParams(criteria: OrderSearchCriteria) {
    return {
      q: criteria.q || null,
      status: criteria.status || null,
      from: criteria.from || null,
      to: criteria.to || null,
      page: criteria.page || null,
    };
  }

  reload(criteria: OrderSearchCriteria = this.criteria): void {
    this.criteria = criteria;
    const version = ++this.loadVersion;
    this.loading = true;
    this.loadError = '';
    this.detailUnavailable = false;
    if (this.detailId !== null) {
      this.loadDetail(this.detailId, version);
      return;
    }
    forkJoin({
      page: this.api.searchOrders(criteria),
      products: this.api.allProducts().pipe(catchError(() => of([]))),
    })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: ({ page, products }) => {
          if (version !== this.loadVersion) return;
          this.page = page;
          this.criteria = { ...criteria, page: page.page };
          const catalogProducts = new Map(products.map((product) => [product.id, product]));
          this.orders = page.orders.map((order) => ({
            ...order,
            items: order.items.map((item) => ({
              ...item,
              productName: catalogProducts.get(item.productId)?.name ?? item.productName,
              thumbnailUrl: catalogProducts.get(item.productId)?.thumbnailUrl ?? item.thumbnailUrl,
            })),
          }));
          this.orders.forEach((order) => this.initializeOrder(order));
          if (!this.expansionInitialized && this.orders.length) {
            this.expandedOrders.add(this.orders[0].id);
            this.expansionInitialized = true;
          }
          this.loading = false;
          this.changeDetector.markForCheck();
        },
        error: () => {
          if (version !== this.loadVersion) return;
          this.page = undefined;
          this.orders = [];
          this.loading = false;
          this.loadError = this.i18n.t('ordersLoadFailed');
          this.changeDetector.markForCheck();
        },
      });
  }

  private loadDetail(value: string, version: number): void {
    const id = Number(value);
    if (!/^\d+$/.test(value) || !Number.isSafeInteger(id) || id <= 0) {
      this.loading = false;
      this.detailUnavailable = true;
      return;
    }
    this.api
      .purchaseOrder(id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (order) => {
          if (version !== this.loadVersion) return;
          this.orders = [order];
          this.initializeOrder(order);
          this.loading = false;
          this.changeDetector.markForCheck();
          this.focusOrderDetail();
        },
        error: (error) => {
          if (version !== this.loadVersion) return;
          this.orders = [];
          this.loading = false;
          this.detailUnavailable = [403, 404].includes(error.status);
          if (!this.detailUnavailable) this.loadError = this.i18n.t('ordersLoadFailed');
          this.changeDetector.markForCheck();
        },
      });
  }

  private focusOrderDetail(): void {
    const version = this.loadVersion;
    afterNextRender(
      () => {
        if (version !== this.loadVersion) return;
        const fragment = orderDetailSections.find((section) => section === this.route.snapshot.fragment);
        const host = this.element.nativeElement;
        const section = fragment ? host.querySelector<HTMLElement>(`#${fragment}`) : null;
        const target = section ?? host.querySelector<HTMLElement>('#order-detail-heading');
        target?.focus({ preventScroll: true });
        target?.scrollIntoView({ block: 'start' });
      },
      { injector: this.injector },
    );
  }

  selectChannel(order: PurchaseOrder, channel: PaymentChannel): void {
    this.selectedChannels[order.id] = channel;
    this.newAttempt(order);
  }

  newAttempt(order: PurchaseOrder): void {
    this.draftKeys[order.id] = crypto.randomUUID();
    delete this.notices[order.id];
  }

  pay(order: PurchaseOrder, simulateFailure = false): void {
    this.execute(
      order,
      {
        key: this.draftKeys[order.id] ?? crypto.randomUUID(),
        channel: this.selectedChannels[order.id] ?? 'ALIPAY',
        simulateFailure,
      },
      false,
    );
  }

  replayLast(order: PurchaseOrder): void {
    const attempt = this.lastAttempts[order.id];
    if (attempt) this.execute(order, { ...attempt }, true);
  }

  replayOnAnotherChannel(order: PurchaseOrder): void {
    const previous = this.lastAttempts[order.id];
    if (!previous) return;
    const alternative = this.channels.find((channel) => channel.code !== previous.channel)?.code ?? 'WECHAT_PAY';
    this.execute(order, { ...previous, channel: alternative }, true);
  }

  channel(order: PurchaseOrder): ChannelOption {
    const code = this.selectedChannels[order.id] ?? 'ALIPAY';
    return this.channels.find((channel) => channel.code === code) ?? this.channels[0];
  }

  trackOrder(_index: number, order: PurchaseOrder): number {
    return order.id;
  }

  hasAction(order: PurchaseOrder, action: OrderAction): boolean {
    return order.availableActions.includes(action);
  }

  showPaymentState(order: PurchaseOrder): boolean {
    return !!order.payment && !['SUCCESS', 'CLOSED'].includes(order.payment.status);
  }

  markProductImageUnavailable(itemId: number): void {
    this.unavailableProductImages = new Set([...this.unavailableProductImages, itemId]);
    this.changeDetector.markForCheck();
  }

  toggleDetails(order: PurchaseOrder): void {
    if (this.expandedOrders.has(order.id)) this.expandedOrders.delete(order.id);
    else this.expandedOrders.add(order.id);
  }

  showPayment(order: PurchaseOrder): void {
    this.expandedOrders.add(order.id);
  }

  showRefund(order: PurchaseOrder): void {
    this.expandedOrders.add(order.id);
    this.toggleRefund(order);
  }

  visibleOrderEvents(order: PurchaseOrder): OrderEvent[] {
    const eventIds = new Set<number>();
    return (order.events ?? []).filter((event) => {
      if (eventIds.has(event.id)) return false;
      eventIds.add(event.id);
      if (event.eventType !== 'SHIPMENT_UPDATED' || !event.detail) return true;
      const change = event.detail.split(' -> ', 2);
      return change.length !== 2 || change[0].trim() !== change[1].trim();
    });
  }

  toggleCancel(order: PurchaseOrder): void {
    if (this.cancelForms.has(order.id)) this.cancelForms.delete(order.id);
    else {
      this.cancelForms.add(order.id);
      this.cancellationReasons[order.id] ??= '';
    }
  }

  cancelOrder(order: PurchaseOrder): void {
    if (this.cancelling.has(order.id)) return;
    this.cancelling.add(order.id);
    delete this.notices[order.id];
    this.api
      .cancelOrder(order.id, this.cancellationReasons[order.id] ?? '')
      .pipe(
        switchMap((updated) => {
          this.orders = this.orders.map((value) => (value.id === updated.id ? updated : value));
          this.cancelForms.delete(order.id);
          this.changeDetector.markForCheck();
          return updated.status === 'CANCELLING'
            ? this.awaitOrder(order.id, (current) => current.status !== 'CANCELLING')
            : of(updated);
        }),
        takeUntilDestroyed(this.destroyRef),
        finalize(() => {
          this.cancelling.delete(order.id);
          this.changeDetector.markForCheck();
        }),
      )
      .subscribe({
        next: (updated) => {
          this.orders = this.orders.map((value) => (value.id === updated.id ? updated : value));
          this.cancelForms.delete(order.id);
          this.notices[order.id] =
            updated.status === 'CANCELLED'
              ? { type: 'success', text: this.i18n.t('orderCancelled') }
              : { type: 'danger', text: this.i18n.t('orderCancelFailed') };
        },
        error: (error) =>
          (this.notices[order.id] =
            error instanceof TimeoutError
              ? { type: 'info', text: this.i18n.t('orderStatusPending') }
              : { type: 'danger', text: error.error?.message ?? this.i18n.t('orderCancelFailed') }),
      });
  }

  refresh(order: PurchaseOrder): void {
    if (this.refreshingPayment.has(order.id)) return;
    this.refreshingPayment.add(order.id);
    this.api
      .purchaseOrder(order.id)
      .pipe(
        switchMap((updated) => {
          this.updatePaymentOrder(updated, 'waitingCallback');
          this.changeDetector.markForCheck();
          return this.paymentAwaitingOrder(updated)
            ? this.awaitOrder(order.id, (current) => current.status !== 'PENDING_PAYMENT')
            : of(updated);
        }),
        takeUntilDestroyed(this.destroyRef),
        finalize(() => {
          this.refreshingPayment.delete(order.id);
          this.changeDetector.markForCheck();
        }),
      )
      .subscribe({
        next: (updated) => {
          this.updatePaymentOrder(updated, 'waitingCallback');
        },
        error: (error) => {
          this.notices[order.id] =
            error instanceof TimeoutError
              ? { type: 'info', text: this.i18n.t('paymentAwaitingOrder') }
              : { type: 'danger', text: error.error?.message ?? this.i18n.t('requestFailed') };
        },
      });
  }

  paymentAwaitingOrder(order: PurchaseOrder): boolean {
    return order.status === 'PENDING_PAYMENT' && order.payment?.status === 'SUCCESS';
  }

  openCheckout(order: PurchaseOrder): void {
    const url = order.payment?.checkoutUrl;
    if (url) {
      window.open(url, 'payment-checkout', 'width=1040,height=760');
      this.notices[order.id] = {
        type: 'info',
        text: this.i18n.t('cashierOpened'),
      };
    }
  }

  receive(order: PurchaseOrder): void {
    if (this.receiving.has(order.id)) return;
    this.receiving.add(order.id);
    delete this.notices[order.id];
    this.api
      .receiveOrder(order.id)
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => {
          this.receiving.delete(order.id);
          this.changeDetector.markForCheck();
        }),
      )
      .subscribe({
        next: (updated) => {
          this.orders = this.orders.map((o) => (o.id === updated.id ? updated : o));
          this.notices[order.id] = { type: 'success', text: this.i18n.t('receiptConfirmed') };
        },
        error: (error) => {
          this.notices[order.id] = { type: 'danger', text: error.error?.message ?? this.i18n.t('requestFailed') };
        },
      });
  }

  async copyTracking(order: PurchaseOrder): Promise<void> {
    if (!order.logistics?.trackingNumber) return;
    await navigator.clipboard.writeText(order.logistics.trackingNumber);
    this.notices[order.id] = { type: 'success', text: this.i18n.t('trackingCopied') };
  }

  repurchase(order: PurchaseOrder): void {
    if (!this.hasAction(order, 'REPURCHASE') || this.repurchasing.has(order.id)) return;
    const storageKey = `commerceflow:repurchase:${order.id}`;
    const requestId = sessionStorage.getItem(storageKey) ?? crypto.randomUUID();
    sessionStorage.setItem(storageKey, requestId);
    this.repurchasing.add(order.id);
    delete this.repurchaseResults[order.id];
    delete this.notices[order.id];
    this.api
      .repurchase(order.id, requestId)
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => {
          this.repurchasing.delete(order.id);
          this.changeDetector.markForCheck();
        }),
      )
      .subscribe({
        next: (result) => {
          this.repurchaseResults[order.id] = result;
          sessionStorage.removeItem(storageKey);
        },
        error: () => {
          this.notices[order.id] = { type: 'danger', text: this.i18n.t('repurchaseFailed') };
        },
      });
  }

  review(order: PurchaseOrder, itemId: number): void {
    if (!this.canSubmitReview(itemId)) return;
    this.reviewing.add(itemId);
    this.api
      .reviewOrderItem(itemId, this.ratings[itemId] || 5, this.reviewComments[itemId].trim())
      .pipe(finalize(() => this.reviewing.delete(itemId)))
      .subscribe({
        next: () => {
          this.reviewed.add(itemId);
          this.notices[order.id] = {
            type: 'success',
            text: this.i18n.t('reviewSubmitted'),
          };
        },
        error: (error) => {
          this.notices[order.id] = {
            type: 'danger',
            text: error.error?.message ?? this.i18n.t('reviewFailed'),
          };
        },
      });
  }

  setRating(itemId: number, score: number): void {
    this.ratings[itemId] = score;
  }

  canSubmitReview(itemId: number): boolean {
    return Boolean(this.reviewComments[itemId]?.trim()) && !this.reviewing.has(itemId);
  }

  toggleRefund(order: PurchaseOrder): void {
    if (this.refundForms.has(order.id)) this.refundForms.delete(order.id);
    else {
      this.refundForms.add(order.id);
      this.refundReasons[order.id] ??= 'CUSTOMER_CANCELLED';
      this.refundKeys[order.id] ??= crypto.randomUUID();
    }
  }

  requestRefund(order: PurchaseOrder): void {
    if (this.refunding.has(order.id)) return;
    this.refunding.add(order.id);
    delete this.notices[order.id];
    this.api
      .requestRefund(
        order.id,
        this.refundKeys[order.id] ?? crypto.randomUUID(),
        this.refundReasons[order.id] ?? 'CUSTOMER_CANCELLED',
        this.refundDetails[order.id] ?? '',
      )
      .pipe(
        switchMap((refund) => {
          if (!['SUCCEEDED', 'FAILED'].includes(refund.status)) {
            this.orders = this.orders.map((value) =>
              value.id === order.id ? { ...value, status: 'REFUNDING', refund } : value,
            );
            this.notices[order.id] = {
              type: 'info',
              text: this.i18n.t('refundProcessing', { refund: refund.refundNumber }),
            };
          }
          this.refundForms.delete(order.id);
          this.changeDetector.markForCheck();
          return this.awaitOrder(
            order.id,
            (current) =>
              current.refund?.refundNumber === refund.refundNumber &&
              ((current.refund.status === 'SUCCEEDED' && current.status === 'REFUNDED') ||
                (current.refund.status === 'FAILED' && current.status !== 'REFUNDING')),
          );
        }),
        takeUntilDestroyed(this.destroyRef),
        finalize(() => {
          this.refunding.delete(order.id);
          this.changeDetector.markForCheck();
        }),
      )
      .subscribe({
        next: (updated) => {
          this.orders = this.orders.map((value) => (value.id === updated.id ? updated : value));
          this.notices[order.id] =
            updated.refund?.status === 'SUCCEEDED'
              ? { type: 'success', text: this.i18n.t('refundSucceeded', { refund: updated.refund.refundNumber }) }
              : { type: 'danger', text: updated.refund?.failureReason || this.i18n.t('refundFailed') };
        },
        error: (error) =>
          (this.notices[order.id] =
            error instanceof TimeoutError
              ? { type: 'info', text: this.i18n.t('orderStatusPending') }
              : { type: 'danger', text: error.error?.message ?? this.i18n.t('refundFailed') }),
      });
  }

  private awaitOrder(id: number, complete: (order: PurchaseOrder) => boolean): Observable<PurchaseOrder> {
    return timer(0, 1000).pipe(
      exhaustMap(() => this.api.purchaseOrder(id)),
      filter(complete),
      take(1),
      timeout({ first: 60000 }),
    );
  }

  private execute(order: PurchaseOrder, attempt: PaymentAttempt, replay: boolean): void {
    if (this.paying.has(order.id)) return;
    this.paying.add(order.id);
    this.notices[order.id] = {
      type: 'info',
      text: this.i18n.t(replay ? 'replayingRequest' : 'submittingPayment'),
    };
    this.api
      .payOrder(order.id, attempt.key, attempt.simulateFailure, attempt.channel)
      .pipe(
        finalize(() => {
          this.paying.delete(order.id);
          this.changeDetector.markForCheck();
        }),
      )
      .subscribe({
        next: (updated) => {
          attempt.paymentNumber = updated.payment?.paymentNumber;
          this.lastAttempts[order.id] = attempt;
          this.persist(order.id, attempt);
          this.updatePaymentOrder(updated, 'paymentPending');
        },
        error: (error) => {
          if (!replay || !this.lastAttempts[order.id]) {
            this.lastAttempts[order.id] = attempt;
            this.persist(order.id, attempt);
          }
          this.notices[order.id] = {
            type: 'danger',
            text: error.error?.message ?? this.i18n.t('paymentRequestFailed'),
          };
        },
      });
  }

  private initializeOrder(order: PurchaseOrder): void {
    const stored = sessionStorage.getItem(this.storageKey(order.id));
    if (stored) {
      try {
        const attempt = JSON.parse(stored) as PaymentAttempt;
        this.lastAttempts[order.id] = attempt;
        this.selectedChannels[order.id] = attempt.channel;
        this.draftKeys[order.id] = attempt.key;
        this.prepareRetry(order);
        return;
      } catch {
        sessionStorage.removeItem(this.storageKey(order.id));
      }
    }
    this.selectedChannels[order.id] = (order.payment?.channel as PaymentChannel) ?? 'ALIPAY';
    this.draftKeys[order.id] = crypto.randomUUID();
  }

  private updatePaymentOrder(order: PurchaseOrder, pendingKey: string): void {
    this.orders = this.orders.map((item) => (item.id === order.id ? order : item));
    this.prepareRetry(order);
    const status = order.payment?.status;
    if (this.paymentAwaitingOrder(order)) {
      this.notices[order.id] = { type: 'info', text: this.i18n.t('paymentAwaitingOrder') };
    } else if (status === 'SUCCESS') {
      this.notices[order.id] = { type: 'success', text: this.i18n.t('signedCallbackPaid') };
    } else if (this.canRetryPayment(order)) {
      this.notices[order.id] = {
        type: 'warning',
        text: this.i18n.t(status === 'EXPIRED' ? 'paymentExpiredRetry' : 'paymentEndedRetry'),
      };
    } else if (status === 'PENDING' || !status) {
      this.notices[order.id] = {
        type: 'info',
        text: this.i18n.t(pendingKey, { payment: order.payment?.paymentNumber ?? '' }),
      };
    } else {
      delete this.notices[order.id];
    }
  }

  private canRetryPayment(order: PurchaseOrder): boolean {
    return (
      !!order.payment && this.hasAction(order, 'PAY') && ['FAILED', 'EXPIRED', 'CLOSED'].includes(order.payment.status)
    );
  }

  private prepareRetry(order: PurchaseOrder): void {
    if (!this.canRetryPayment(order)) return;
    const previous = this.lastAttempts[order.id];
    if (
      previous?.paymentNumber &&
      previous.paymentNumber === order.payment?.paymentNumber &&
      this.draftKeys[order.id] === previous.key
    ) {
      this.draftKeys[order.id] = crypto.randomUUID();
    }
  }

  private persist(orderId: number, attempt: PaymentAttempt): void {
    sessionStorage.setItem(this.storageKey(orderId), JSON.stringify(attempt));
  }

  private storageKey(orderId: number): string {
    return `commerceflow-payment-attempt-${orderId}`;
  }
}
