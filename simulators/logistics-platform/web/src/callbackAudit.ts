import type { AuditEvent } from './api';

export const callbackAuditTypes = {
  failure: '投递失败',
  retry: '自动重试',
  replay: '手动重新投递',
  success: '投递成功',
};

export type CallbackAuditType = keyof typeof callbackAuditTypes;

export function callbackAuditText(event: AuditEvent): {
  title: string;
  type?: CallbackAuditType;
  detail?: string | null;
  result?: string;
} {
  switch (event.eventType) {
    case 'OUTBOX_CREATED':
      return { title: '加入投递队列' };
    case 'DELIVERY_CLAIMED': {
      const attempt = /^Attempt (\d+) of (\d+)$/.exec(event.reason ?? '');
      const retry = attempt !== null && Number(attempt[1]) > 1;
      return {
        title: retry ? '开始重试' : '开始投递',
        type: retry ? 'retry' : undefined,
        detail: attempt ? `第 ${attempt[1]} 次尝试，共允许 ${attempt[2]} 次` : undefined,
      };
    }
    case 'DELIVERY_FAILED':
      return {
        title: callbackAuditTypes.failure,
        type: 'failure',
        result: event.toStatus === 'DEAD' ? '已终止' : '等待重试',
        detail: event.reason === 'Logistics callback delivery failed' ? '回调请求未成功' : event.reason,
      };
    case 'MANUAL_REPLAY_REQUESTED':
      return {
        title: callbackAuditTypes.replay,
        type: 'replay',
        detail: [`操作人：${event.operatedBy}`, event.reason && `重投原因：${event.reason}`]
          .filter(Boolean)
          .join(' · '),
      };
    case 'DELIVERY_PUBLISHED':
      return { title: callbackAuditTypes.success, type: 'success', detail: '接收端已接受回调' };
    default:
      return { title: '投递审计事件', detail: event.reason || event.eventType };
  }
}
