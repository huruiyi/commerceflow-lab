import { ParamMap } from '@angular/router';
import { OrderSearchCriteria, orderSearchStatuses } from 'src/model/IPurchaseOrder';
import { readPage } from '../list-pagination/page-query';

export function readOrderSearch(params: ParamMap): OrderSearchCriteria {
  const status = params.get('status') ?? '';
  const from = params.get('from') ?? '';
  const to = params.get('to') ?? '';
  const date = /^\d{4}-\d{2}-\d{2}$/;
  return {
    q: (params.get('q') ?? '').trim().slice(0, 120),
    status: orderSearchStatuses.includes(status) ? status : '',
    from: date.test(from) ? from : '',
    to: date.test(to) && (!date.test(from) || from <= to) ? to : '',
    page: readPage(params.get('page')),
  };
}

export function orderSearchParams(criteria: OrderSearchCriteria) {
  return {
    q: criteria.q || null,
    status: criteria.status || null,
    from: criteria.from || null,
    to: criteria.to || null,
    page: criteria.page || null,
  };
}
