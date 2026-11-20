import { expect, test } from 'vitest';
import { summarizePayments } from './payment-statistics';

test('bins creation and payment separately by calendar day and preserves exact cents', () => {
  const payment = {
    channel: 'ALIPAY',
    status: 'SUCCESS',
    faultScenario: 'NONE',
    amount: 0.1,
    createdAt: '2026-11-18T15:55:00Z',
    paidAt: '2026-11-18T16:05:00Z',
  };
  const history = {
    ...payment,
    channel: 'WECHAT_PAY',
    status: 'CLOSED',
    faultScenario: 'DELAYED_SUCCESS',
    createdAt: '2026-10-01T00:00:00Z',
    paidAt: undefined,
  };
  const records = [payment, { ...payment, amount: 0.2 }, history];
  const now = new Date('2026-11-19T16:10:00Z');
  const shanghai = summarizePayments(records, 'Asia/Shanghai', now);
  expect(shanghai.days).toHaveLength(30);
  expect(shanghai.days.slice(-3)).toEqual([
    { date: '2026-11-18', created: 2, paid: 0, cents: 0 },
    { date: '2026-11-19', created: 0, paid: 2, cents: 30 },
    { date: '2026-11-20', created: 0, paid: 0, cents: 0 },
  ]);
  expect(shanghai.channels.get('UNION_PAY')).toEqual({ created: 0, successful: 0 });
  expect(shanghai.statuses.get('CLOSED')).toBe(1);
  expect(shanghai.scenarios.get('DELAYED_SUCCESS')).toBe(1);
  const newYork = summarizePayments(records, 'America/New_York', now);
  expect(newYork.days.at(-2)).toEqual({ date: '2026-11-18', created: 2, paid: 2, cents: 30 });
});
