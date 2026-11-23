import { afterNextRender, ChangeDetectorRef, Component, DestroyRef, ElementRef, Injector, OnInit } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, NavigationSkipped, NavigationSkippedCode, Router } from '@angular/router';
import {
  distinctUntilChanged,
  exhaustMap,
  filter,
  finalize,
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
  PaymentChannel,
  PurchaseOrder,
  RepurchaseResult,
} from 'src/model/IPurchaseOrder';
import { orderSearchParams, readOrderSearch } from '../order-list-filter/order-search-query';
import { AccountService } from '../services/account.service';
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
  awaitingReceipt?: boolean;
}

interface Notice {
  type: 'success' | 'warning' | 'danger' | 'info';
  text: string;
}

@Component({
  standalone: false,
  selector: 'app-purchase-orders',
  templateUrl: './purchase-orders.component.html',
  styleUrls: ['./order-list-layout.css', './purchase-orders.component.css'],
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
  cancellationRequests: Record<number, string | undefined> = {};
  unavailableProductImages = new Set<number>();
  expandedOrders = new Set<number>();
  repurchasing = new Set<number>();
  repurchaseResults: Record<number, RepurchaseResult> = {};
  loading = true;
  loadError = '';
  detailId: string | null = null;
  detailUnavailable = false;
  private loadVersion = 0;

  constructor(
    protected account: AccountService,
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
        this.criteria = readOrderSearch(params);
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
        .subscribe((params) => this.reload(readOrderSearch(params)));
    }
    this.i18n.localeChanged.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => this.reload());
  }

  get listQueryParams() {
    return orderSearchParams(this.criteria);
  }

  applyCriteria(criteria: OrderSearchCriteria): void {
    void this.router.navigate([], { relativeTo: this.route, queryParams: orderSearchParams(criteria) });
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
    this.api
      .searchOrders(criteria)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (page) => {
          if (version !== this.loadVersion) return;
          this.page = page;
          this.criteria = { ...criteria, page: page.page };
          this.orders = page.orders;
          this.orders.forEach((order) => this.initializeListOrder(order));
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
    if (this.paymentNeedsRecovery(order)) return;
    this.selectedChannels[order.id] = channel;
    this.newAttempt(order);
  }

  newAttempt(order: PurchaseOrder): void {
    if (this.paymentNeedsRecovery(order)) return;
    this.draftKeys[order.id] = crypto.randomUUID();
    delete this.notices[order.id];
  }

  pay(order: PurchaseOrder, simulateFailure = false): void {
    if (this.paymentNeedsRecovery(order) || this.cancellationRequests[order.id] !== undefined) return;
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
    if (!previous || previous.awaitingReceipt) return;
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
    if (this.cancelling.has(order.id) || this.paying.has(order.id)) return;
    const reason = this.cancellationRequests[order.id] ?? this.cancellationReasons[order.id] ?? '';
    this.cancellationRequests[order.id] = reason;
    this.writeRequest(this.requestKey(order.id, 'cancel'), reason);
    this.observeCancellation(order, this.api.cancelOrder(order.id, reason));
  }

  checkCancellation(order: PurchaseOrder): void {
    if (this.cancelling.has(order.id)) return;
    this.observeCancellation(order, this.api.purchaseOrder(order.id));
  }

  private observeCancellation(order: PurchaseOrder, request: Observable<PurchaseOrder>): void {
    this.cancelling.add(order.id);
    delete this.notices[order.id];
    request
      .pipe(
        timeout(8000),
        switchMap((updated) => {
          this.orders = this.orders.map((value) => (value.id === updated.id ? updated : value));
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
          this.restoreCancellation(updated);
        },
        error: () => {
          this.notices[order.id] = { type: 'info', text: this.i18n.t('cancelReceiptUnknown') };
        },
      });
  }

  private restoreCancellation(order: PurchaseOrder): void {
    const reason = this.cancellationRequests[order.id] ?? this.readRequest(this.requestKey(order.id, 'cancel'));
    if (reason === null) return;
    if (['PENDING_PAYMENT', 'CANCELLING'].includes(order.status)) {
      this.cancellationRequests[order.id] = reason;
      this.cancellationReasons[order.id] = reason;
      if (order.status === 'CANCELLING') {
        this.cancelForms.delete(order.id);
        if (!this.cancelling.has(order.id)) this.checkCancellation(order);
      }
      this.notices[order.id] = { type: 'info', text: this.i18n.t('cancelReceiptUnknown') };
      return;
    }
    delete this.cancellationRequests[order.id];
    this.writeRequest(this.requestKey(order.id, 'cancel'), null);
    this.cancelForms.delete(order.id);
    const cancelled = order.status === 'CANCELLED';
    this.notices[order.id] = {
      type: cancelled ? 'success' : 'warning',
      text: this.i18n.t(cancelled ? 'orderCancelled' : 'orderCancelFailed'),
    };
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
            ? this.awaitOrder(order.id, (current) => !this.paymentAwaitingOrder(current))
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
    return (
      order.payment?.status === 'SUCCESS' &&
      (order.status === 'PENDING_PAYMENT' || order.inventoryStatus === 'WAITING_COMMIT')
    );
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
    if (this.paying.has(order.id) || this.cancellationRequests[order.id] !== undefined) return;
    if (!replay || attempt.awaitingReceipt) {
      attempt.awaitingReceipt = true;
      this.lastAttempts[order.id] = attempt;
      this.persist(order.id, attempt);
    }
    this.paying.add(order.id);
    this.notices[order.id] = {
      type: 'info',
      text: this.i18n.t(replay ? 'replayingRequest' : 'submittingPayment'),
    };
    this.api
      .payOrder(order.id, attempt.key, attempt.simulateFailure, attempt.channel)
      .pipe(
        timeout(8000),
        takeUntilDestroyed(this.destroyRef),
        finalize(() => {
          this.paying.delete(order.id);
          this.changeDetector.markForCheck();
        }),
      )
      .subscribe({
        next: (updated) => {
          attempt.awaitingReceipt = false;
          attempt.paymentNumber = updated.payment?.paymentNumber;
          this.lastAttempts[order.id] = attempt;
          this.persist(order.id, attempt);
          this.updatePaymentOrder(updated, 'paymentPending');
        },
        error: (error) => {
          if ([400, 403, 404, 409, 422].includes(error.status) && !replay) attempt.awaitingReceipt = false;
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
    this.restoreCancellation(order);
    const stored = this.readRequest(this.requestKey(order.id, 'payment'));
    if (stored) {
      try {
        const attempt = JSON.parse(stored) as PaymentAttempt;
        this.lastAttempts[order.id] = attempt;
        this.selectedChannels[order.id] = attempt.channel;
        this.draftKeys[order.id] = attempt.key;
        this.recoverPaymentReceipt(order);
        this.prepareRetry(order);
        if (attempt.paymentNumber === order.payment?.paymentNumber && order.payment) {
          this.watchRecoveredPayment(order);
        }
        return;
      } catch {
        this.writeRequest(this.requestKey(order.id, 'payment'), null);
      }
    }
    this.selectedChannels[order.id] = (order.payment?.channel as PaymentChannel) ?? 'ALIPAY';
    this.draftKeys[order.id] = crypto.randomUUID();
  }

  private initializeListOrder(order: PurchaseOrder): void {
    const firstVisit = this.draftKeys[order.id] === undefined;
    this.initializeOrder(order);
    const recovering =
      this.cancellationRequests[order.id] !== undefined ||
      this.paymentNeedsRecovery(order) ||
      (this.lastAttempts[order.id]?.paymentNumber === order.payment?.paymentNumber &&
        (order.payment?.status === 'PENDING' || this.paymentAwaitingOrder(order)));
    if (firstVisit && recovering) this.expandedOrders.add(order.id);
  }

  private updatePaymentOrder(order: PurchaseOrder, pendingKey: string): void {
    this.orders = this.orders.map((item) => (item.id === order.id ? order : item));
    this.recoverPaymentReceipt(order);
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
    this.writeRequest(this.requestKey(orderId, 'payment'), JSON.stringify(attempt));
  }

  paymentNeedsRecovery(order: PurchaseOrder): boolean {
    return this.lastAttempts[order.id]?.awaitingReceipt === true && this.hasAction(order, 'PAY');
  }

  private recoverPaymentReceipt(order: PurchaseOrder): void {
    const attempt = this.lastAttempts[order.id];
    if (!attempt?.awaitingReceipt) return;
    if (order.payment?.idempotencyKey === attempt.key) {
      attempt.awaitingReceipt = false;
      attempt.paymentNumber = order.payment.paymentNumber;
      this.persist(order.id, attempt);
      this.notices[order.id] = { type: 'info', text: this.i18n.t('paymentReceiptRecovered') };
    } else if (this.paymentNeedsRecovery(order)) {
      this.notices[order.id] = { type: 'info', text: this.i18n.t('paymentReceiptUnknown') };
    }
  }

  private watchRecoveredPayment(order: PurchaseOrder): void {
    if (
      this.refreshingPayment.has(order.id) ||
      (order.payment?.status !== 'PENDING' && !this.paymentAwaitingOrder(order))
    )
      return;
    this.refreshingPayment.add(order.id);
    this.awaitOrder(order.id, (current) => current.payment?.status !== 'PENDING' && !this.paymentAwaitingOrder(current))
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => {
          this.refreshingPayment.delete(order.id);
          this.changeDetector.markForCheck();
        }),
      )
      .subscribe({
        next: (current) => this.updatePaymentOrder(current, 'waitingCallback'),
        error: () => undefined,
      });
  }

  private requestKey(orderId: number, operation: 'payment' | 'cancel'): string {
    return `commerceflow-order:${this.account.userProfile()?.id}:${orderId}:${operation}`;
  }

  private readRequest(key: string): string | null {
    try {
      return sessionStorage.getItem(key);
    } catch {
      return null;
    }
  }

  private writeRequest(key: string, value: string | null): void {
    try {
      if (value === null) sessionStorage.removeItem(key);
      else sessionStorage.setItem(key, value);
    } catch {
      // Keep the in-memory request when tab storage is unavailable.
    }
  }
}
