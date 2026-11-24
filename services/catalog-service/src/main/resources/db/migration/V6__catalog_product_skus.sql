CREATE TABLE catalog_product_skus (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '供门户选择的稳定 SKU 编号',
  product_id BIGINT NOT NULL COMMENT '所属公开商品',
  merchant_id BIGINT NOT NULL COMMENT 'SKU 编码所属店铺',
  code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_general_ci NOT NULL COMMENT '店内唯一编码',
  spec_key CHAR(64) CHARACTER SET ascii NOT NULL COMMENT '规范化规格组合摘要',
  specifications JSON NOT NULL COMMENT '用户可读的规格名和值',
  default_sku BOOLEAN NOT NULL COMMENT '从单规格商品延续的默认销售单元',
  default_product_id BIGINT GENERATED ALWAYS AS (CASE WHEN default_sku THEN product_id END) STORED,
  unit_price DECIMAL(12,2) NULL COMMENT '非默认 SKU 售价，默认 SKU 读取原商品价格',
  lifecycle_status VARCHAR(12) NULL COMMENT '非默认 SKU 的 ACTIVE 或 INACTIVE，默认 SKU 继承原商品',
  business_version BIGINT NOT NULL DEFAULT 1 COMMENT 'SKU 编辑的乐观锁版本',
  request_id CHAR(36) NULL COMMENT '创建请求号，用于安全重试',
  create_fingerprint CHAR(64) NULL COMMENT '首次请求内容摘要',
  stock_product_id BIGINT NULL COMMENT '库存与历史交易使用的销售单元编号',
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  CONSTRAINT fk_catalog_sku_product FOREIGN KEY (product_id) REFERENCES products(id),
  CONSTRAINT uk_catalog_sku_code UNIQUE (merchant_id,code),
  CONSTRAINT uk_catalog_sku_specs UNIQUE (product_id,spec_key),
  CONSTRAINT uk_catalog_sku_default UNIQUE (default_product_id),
  CONSTRAINT uk_catalog_sku_request UNIQUE (merchant_id,request_id),
  CONSTRAINT uk_catalog_sku_stock UNIQUE (stock_product_id),
  CONSTRAINT ck_catalog_sku_kind CHECK ((default_sku=TRUE AND unit_price IS NULL AND lifecycle_status IS NULL)
    OR (default_sku=FALSE AND unit_price IS NOT NULL AND unit_price>=0
      AND lifecycle_status IS NOT NULL AND lifecycle_status IN ('ACTIVE','INACTIVE')))
);

INSERT INTO catalog_product_skus(product_id,merchant_id,code,spec_key,specifications,default_sku,stock_product_id)
SELECT id,merchant_id,CONCAT('DEFAULT-',id),SHA2('{}',256),'{}',TRUE,id FROM products;
