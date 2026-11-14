CREATE TABLE notification_storage_state (
  id TINYINT NOT NULL COMMENT '固定为 1 的运行时切换状态主键',
  source_watermark BIGINT NOT NULL DEFAULT 0 COMMENT '已完成回填的商城通知主键水位',
  backfill_completed BOOLEAN NOT NULL DEFAULT FALSE COMMENT '历史通知是否已追平',
  completed_at TIMESTAMP(6) NULL COMMENT '最近一次完成历史追平的时间',
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
    ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '状态更新时间',
  PRIMARY KEY (id),
  CONSTRAINT ck_notification_storage_state_singleton CHECK (id=1)
) COMMENT='notification 独立 Schema 历史回填水位';

INSERT INTO notification_storage_state(id) VALUES(1);

CREATE TABLE notification_storage_audits (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '独立 Schema 核对批次主键',
  matched_count INT NOT NULL COMMENT '两个 Schema 业务字段一致的通知数',
  missing_count INT NOT NULL COMMENT '商城存在但独立 Schema 缺失的通知数',
  orphan_count INT NOT NULL COMMENT '独立 Schema 存在但商城缺失的通知数',
  mismatch_count INT NOT NULL COMMENT '同事件业务字段或已读状态不一致的通知数',
  backfill_completed BOOLEAN NOT NULL COMMENT '检查时历史回填是否完成',
  passed BOOLEAN NOT NULL COMMENT '本次是否满足切换条件',
  checked_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '检查完成时间',
  PRIMARY KEY (id),
  KEY ix_notification_storage_audit_time (checked_at)
) COMMENT='notification 独立运行 Schema 切换核对证据';
