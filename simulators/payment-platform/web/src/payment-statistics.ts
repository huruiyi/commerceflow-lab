import type { Transaction } from './types';

type PaymentRecord = Pick<Transaction, 'channel' | 'status' | 'faultScenario' | 'createdAt' | 'paidAt' | 'amount'>;

export function summarizePayments(records: PaymentRecord[], timeZone: string, now = new Date()) {
  const dateFormat = new Intl.DateTimeFormat('en', { timeZone, year: 'numeric', month: '2-digit', day: '2-digit' });
  function calendarDate(date: Date) {
    const parts = dateFormat.formatToParts(date);
    const part = (type: string) => parts.find((item) => item.type === type)?.value;
    return [part('year'), part('month'), part('day')].join('-');
  }
  const today = new Date(calendarDate(now) + 'T00:00:00Z');
  const days = Array.from({ length: 30 }, (_, index) => {
    const day = new Date(today);
    day.setUTCDate(today.getUTCDate() - 29 + index);
    return { date: day.toISOString().slice(0, 10), created: 0, paid: 0, cents: 0 };
  });
  const byDate = new Map(days.map((day) => [day.date, day]));
  const channels = new Map(
    ['ALIPAY', 'WECHAT_PAY', 'UNION_PAY'].map((channel) => [channel, { created: 0, successful: 0 }]),
  );
  const statuses = new Map<string, number>();
  const scenarios = new Map<string, number>();

  for (const record of records) {
    const channel = channels.get(record.channel) ?? { created: 0, successful: 0 };
    channel.created++;
    if (record.status === 'SUCCESS') channel.successful++;
    channels.set(record.channel, channel);
    statuses.set(record.status, (statuses.get(record.status) ?? 0) + 1);
    scenarios.set(record.faultScenario, (scenarios.get(record.faultScenario) ?? 0) + 1);

    const created = byDate.get(calendarDate(new Date(record.createdAt)));
    if (created) created.created++;
    if (!record.paidAt) continue;
    const paid = byDate.get(calendarDate(new Date(record.paidAt)));
    if (paid) {
      paid.paid++;
      paid.cents += Math.round(record.amount * 100);
    }
  }

  return { days, channels, statuses, scenarios };
}
