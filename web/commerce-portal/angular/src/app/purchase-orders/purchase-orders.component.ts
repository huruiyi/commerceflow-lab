import {
  ChangeDetectorRef,
  Component,
  DestroyRef,
  OnInit,
} from '@angular/core';
import {takeUntilDestroyed} from '@angular/core/rxjs-interop';
import {finalize, forkJoin} from 'rxjs';
import {OrderAction, OrderEvent, PaymentChannel, PurchaseOrder} from 'src/model/IPurchaseOrder';
import {ApiService} from '../services/api.service';
import {I18nService} from '../services/i18n.service';

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
  paying = new Set<number>();
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
  cancellationReasons: Record<number, string> = {};
  expandedOrders = new Set<number>();
  loading = true;
  loadError = '';
  private loadVersion = 0;
  private expansionInitialized = false;

  constructor(
    private api: ApiService,
    private changeDetector: ChangeDetectorRef,
    public i18n: I18nService,
    private destroyRef: DestroyRef,
  ) {
  }

  ngOnInit(): void {
    this.reload();
    this.i18n.localeChanged
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.reload());
  }

  reload(): void {
    const version = ++this.loadVersion;
    this.loading = true;
    this.loadError = '';
    forkJoin({
      orders: this.api.purchaseOrders(),
      products: this.api.allProducts(),
    }).subscribe({
      next: ({orders, products}) => {
        if (version !== this.loadVersion) return;
        const names = new Map(
          products.map((product) => [product.id, product.name]),
        );
        this.orders = orders.map((order) => ({
          ...order,
          items: order.items.map((item) => ({
            ...item,
            productName: names.get(item.productId) ?? item.productName,
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
        this.loading = false;
        this.loadError = this.i18n.t('ordersLoadFailed');
        this.changeDetector.markForCheck();
      },
    });
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
    if (attempt) this.execute(order, {...attempt}, true);
  }

  replayOnAnotherChannel(order: PurchaseOrder): void {
    const previous = this.lastAttempts[order.id];
    if (!previous) return;
    const alternative =
      this.channels.find((channel) => channel.code !== previous.channel)
        ?.code ?? 'WECHAT_PAY';
    this.execute(order, {...previous, channel: alternative}, true);
  }

  channel(order: PurchaseOrder): ChannelOption {
    const code = this.selectedChannels[order.id] ?? 'ALIPAY';
    return (
      this.channels.find((channel) => channel.code === code) ?? this.channels[0]
    );
  }

  trackOrder(_index: number, order: PurchaseOrder): number {
    return order.id;
  }

  hasAction(order: PurchaseOrder, action: OrderAction): boolean {
    return order.availableActions.includes(action);
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
    this.cancelling.add(order.id);
    this.api.cancelOrder(order.id, this.cancellationReasons[order.id] ?? '')
      .pipe(finalize(() => this.cancelling.delete(order.id)))
      .subscribe({
        next: (updated) => {
          this.orders = this.orders.map((value) => value.id === updated.id ? updated : value);
          this.cancelForms.delete(order.id);
          this.notices[order.id] = {type: 'success', text: this.i18n.t('orderCancelled')};
        },
        error: (error) => this.notices[order.id] = {
          type: 'danger', text: error.error?.message ?? this.i18n.t('orderCancelFailed')
        }
      });
  }

  refresh(order: PurchaseOrder): void {
    this.api.purchaseOrder(order.id).subscribe((updated) => {
      this.orders = this.orders.map((item) =>
        item.id === updated.id ? updated : item,
      );
      this.notices[order.id] = {
        type: updated.status === 'PAID' ? 'success' : 'info',
        text: this.i18n.t(
          updated.status === 'PAID' ? 'signedCallbackPaid' : 'waitingCallback',
        ),
      };
      this.changeDetector.markForCheck();
    });
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
    this.api.receiveOrder(order.id).subscribe((updated) => {
      this.orders = this.orders.map((o) => (o.id === updated.id ? updated : o));
      this.notices[order.id] = {
        type: 'success',
        text: this.i18n.t('receiptConfirmed'),
      };
    });
  }

  async copyTracking(order: PurchaseOrder): Promise<void> {
    if (!order.logistics?.trackingNumber) return;
    await navigator.clipboard.writeText(order.logistics.trackingNumber);
    this.notices[order.id] = {type: 'success', text: this.i18n.t('trackingCopied')};
  }

  review(order: PurchaseOrder, itemId: number): void {
    if (!this.canSubmitReview(itemId)) return;
    this.reviewing.add(itemId);
    this.api
      .reviewOrderItem(
        itemId,
        this.ratings[itemId] || 5,
        this.reviewComments[itemId].trim(),
      )
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
    this.refunding.add(order.id);
    this.api
      .requestRefund(
        order.id,
        this.refundKeys[order.id] ?? crypto.randomUUID(),
        this.refundReasons[order.id] ?? 'CUSTOMER_CANCELLED',
        this.refundDetails[order.id] ?? '',
      )
      .pipe(finalize(() => this.refunding.delete(order.id)))
      .subscribe({
        next: (refund) => {
          this.orders = this.orders.map((value) =>
            value.id === order.id
              ? {
                ...value,
                status:
                  refund.status === 'SUCCEEDED' ? 'REFUNDED' : 'REFUNDING',
                refund,
              }
              : value,
          );
          this.refundForms.delete(order.id);
          this.notices[order.id] = {
            type: refund.status === 'SUCCEEDED' ? 'success' : 'info',
            text: this.i18n.t(
              refund.status === 'SUCCEEDED'
                ? 'refundSucceeded'
                : 'refundProcessing',
              {refund: refund.refundNumber},
            ),
          };
        },
        error: (error) =>
          (this.notices[order.id] = {
            type: 'danger',
            text: error.error?.message ?? this.i18n.t('refundFailed'),
          }),
      });
  }

  private execute(
    order: PurchaseOrder,
    attempt: PaymentAttempt,
    replay: boolean,
  ): void {
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
          this.orders = this.orders.map((item) =>
            item.id === updated.id ? updated : item,
          );
          this.notices[order.id] = {
            type: 'info',
            text: this.i18n.t('paymentPending', {
              payment: attempt.paymentNumber ?? '',
            }),
          };
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
        return;
      } catch {
        sessionStorage.removeItem(this.storageKey(order.id));
      }
    }
    this.selectedChannels[order.id] =
      (order.payment?.channel as PaymentChannel) ?? 'ALIPAY';
    this.draftKeys[order.id] = crypto.randomUUID();
  }

  private persist(orderId: number, attempt: PaymentAttempt): void {
    sessionStorage.setItem(this.storageKey(orderId), JSON.stringify(attempt));
  }

  private storageKey(orderId: number): string {
    return `commerceflow-payment-attempt-${orderId}`;
  }
}
