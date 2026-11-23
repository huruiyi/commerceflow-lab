ALTER TABLE catalog_event_outbox
  ADD COLUMN elasticsearch_indexed_at TIMESTAMP(6) NULL COMMENT 'Elasticsearch 投影独立确认时间',
  ADD KEY ix_catalog_elasticsearch_pending(elasticsearch_indexed_at,id);

CREATE TABLE catalog_elasticsearch_state (
  singleton_id INT PRIMARY KEY COMMENT 'Elasticsearch 投影状态固定为 1',
  generation BIGINT NOT NULL COMMENT '当前 Elasticsearch 索引代次，0 表示尚未初始化',
  catalog_generation BIGINT NOT NULL COMMENT '已同步的目录搜索重建代次'
);

INSERT INTO catalog_elasticsearch_state(singleton_id,generation,catalog_generation) VALUES (1,0,0);
