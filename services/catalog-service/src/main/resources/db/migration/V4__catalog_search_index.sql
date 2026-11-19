ALTER TABLE catalog_event_outbox
  ADD COLUMN search_indexed_at TIMESTAMP(6) NULL COMMENT '搜索投影独立确认时间',
  ADD KEY ix_catalog_search_pending(search_indexed_at,id);

CREATE TABLE catalog_search_state (
  singleton_id INT PRIMARY KEY COMMENT '搜索索引状态固定为 1',
  generation BIGINT NOT NULL COMMENT '当前查询代次，0 表示尚未初始化',
  rebuild_requested BOOLEAN NOT NULL COMMENT '是否等待全量重建',
  rebuilt_at TIMESTAMP(6) NULL COMMENT '当前代次的重建完成时间'
);

INSERT INTO catalog_search_state(singleton_id,generation,rebuild_requested) VALUES (1,0,TRUE);

CREATE TABLE catalog_search_documents (
  generation BIGINT NOT NULL COMMENT '所属索引代次',
  product_id BIGINT NOT NULL COMMENT '目录商品编号',
  locale VARCHAR(10) NOT NULL COMMENT '本地化语言，空字符串表示原文',
  merchant_id BIGINT NOT NULL COMMENT '商品店铺编号',
  business_version BIGINT NOT NULL COMMENT '已读取的目录业务版本',
  name VARCHAR(160) NOT NULL COMMENT '商品名称快照',
  brief VARCHAR(300) NOT NULL COMMENT '商品简介快照',
  description VARCHAR(2000) NOT NULL COMMENT '商品详情文本快照',
  searchable TEXT NOT NULL COMMENT '用于中文全文相关性计算的文本',
  unit_price DECIMAL(12,2) NOT NULL COMMENT '用于搜索筛选的商品售价快照',
  PRIMARY KEY(generation,product_id,locale),
  KEY ix_catalog_search_shop_price(generation,merchant_id,unit_price,product_id),
  FULLTEXT KEY ft_catalog_search_text(searchable) WITH PARSER ngram
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
