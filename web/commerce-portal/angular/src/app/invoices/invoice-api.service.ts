import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { catchError, map, Observable, switchMap, throwError } from 'rxjs';

export type InvoiceMode = 'customer' | 'merchant' | 'platform';
export type InvoiceStatus = 'PENDING' | 'ISSUED' | 'EXHAUSTED' | 'REVIEW_REQUIRED';

export interface InvoiceSummary {
  orderId: number;
  orderNumber: string | null;
  applicationNo: string;
  status: InvoiceStatus;
  merchantId: number | null;
  invoiceNo: string | null;
  amount: number | null;
  currency: string | null;
  effectiveAmount: number | null;
}

export interface InvoicePage {
  rows: InvoiceSummary[];
  page: number;
  hasNext: boolean;
}

interface Ticket {
  invoiceNo: string | null;
  referenceNo: string;
  amount: number;
  currency: string;
  status?: 'PENDING' | 'SUCCEEDED' | 'FAILED';
  requestId?: string;
  failureCode?: string;
}

interface ReviewAction {
  actorId: number;
  priorReason: string;
  priorDetail: string | null;
  note: string;
  createdAt: string;
  requestId?: string | null;
}

export interface InvoiceDetail {
  orderId: number;
  orderNumber: string | null;
  applicationNo: string | null;
  status: InvoiceStatus | null;
  effectiveAmount: number | null;
  issue?: Ticket | null;
  redAttempts: Ticket[];
  eligible?: boolean;
  reviewReason?: string;
  reviewDetail?: string;
  issueAttempts?: Ticket[];
  actions?: ReviewAction[];
}

interface OperatorInvoice {
  invoice: InvoiceDetail;
  issueAttempts: Ticket[];
  actions: ReviewAction[];
}

export const invoiceRoutes = {
  customer: '/invoices',
  merchant: '/merchant/invoices',
  platform: '/admin/invoices',
};

@Injectable({ providedIn: 'root' })
export class InvoiceApi {
  private readonly http = inject(HttpClient);

  list(mode: InvoiceMode, q: string, status: string, page: number): Observable<InvoicePage> {
    const params: Record<string, string | number> = { page };
    if (q.trim()) params['q'] = q.trim();
    if (status) params['status'] = status;
    return this.http.get<InvoicePage>('/api' + invoiceRoutes[mode], { params });
  }

  detail(mode: InvoiceMode, orderId: number): Observable<InvoiceDetail> {
    if (mode === 'platform') {
      return this.http.get<OperatorInvoice>(`/api/admin/invoices/${orderId}`).pipe(map(this.operatorDetail));
    }
    if (mode === 'merchant') {
      return this.http
        .get<Omit<InvoiceDetail, 'redAttempts'> & { redTickets: Ticket[] }>(`/api/merchant/invoices/${orderId}`)
        .pipe(map(({ redTickets, ...detail }) => ({ ...detail, redAttempts: redTickets })));
    }
    const url = this.customerUrl(orderId);
    return this.http.get<InvoiceDetail>(url).pipe(
      catchError((error: HttpErrorResponse) =>
        error.status === 404
          ? this.http.get<{ orderNumber: string; eligible: boolean }>(url + '/eligibility').pipe(
              map((result) => ({
                ...result,
                orderId,
                applicationNo: null,
                status: null,
                effectiveAmount: null,
                redAttempts: [],
              })),
            )
          : throwError(() => error),
      ),
    );
  }

  request(orderId: number): Observable<InvoiceDetail> {
    return this.csrf().pipe(
      switchMap(({ headerName, token }) =>
        this.http.post<InvoiceDetail>(this.customerUrl(orderId), {}, { headers: { [headerName]: token } }),
      ),
    );
  }

  recheck(orderId: number, requestId: string, note: string): Observable<InvoiceDetail> {
    return this.csrf().pipe(
      switchMap(({ headerName, token }) =>
        this.http.post<OperatorInvoice>(
          `/api/admin/invoices/${orderId}/review-actions`,
          { requestId, note },
          {
            headers: { [headerName]: token },
          },
        ),
      ),
      map(this.operatorDetail),
    );
  }

  private csrf(): Observable<{ headerName: string; token: string }> {
    return this.http.get<{ headerName: string; token: string }>('/api/auth/csrf');
  }

  private operatorDetail({ invoice, issueAttempts, actions }: OperatorInvoice): InvoiceDetail {
    return { ...invoice, issueAttempts, actions };
  }

  private customerUrl(orderId: number): string {
    return `/api/purchase-orders/${orderId}/invoices`;
  }
}
