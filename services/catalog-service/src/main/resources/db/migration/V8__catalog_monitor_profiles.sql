ALTER TABLE products
  ADD COLUMN monitor_profile JSON NULL COMMENT '公开显示器型号的固定技术参数';

-- 仅迁移四款已发布演示商品及其原导入 SKU，保留商品、销售单元和库存编号。
-- 冻结商品事件版本，迁移固定参数通过搜索重建生效，库存不产生事件版本缺口。
UPDATE products p
JOIN catalog_product_skus sku ON sku.product_id=p.id
  AND sku.request_id=CONCAT('e48f673f-910d-405f-a051-', RIGHT(p.create_request_id, 12))
SET p.monitor_profile=CASE p.create_request_id
  WHEN 'f784543a-5123-432b-9030-000000000001' THEN
    JSON_OBJECT('brand','Cedar','screenSizeInches',24,'widthPixels',1920,'heightPixels',1080,
      'panel','IPS','refreshRateHz',75,'ports',JSON_ARRAY('HDMI','DisplayPort'))
  WHEN 'f784543a-5123-432b-9030-000000000002' THEN
    JSON_OBJECT('brand','Cedar','screenSizeInches',27,'widthPixels',2560,'heightPixels',1440,
      'panel','IPS','refreshRateHz',75,'ports',JSON_ARRAY('HDMI','DisplayPort'))
  WHEN 'f784543a-5123-432b-9030-000000000003' THEN
    JSON_OBJECT('brand','Lumen','screenSizeInches',32,'widthPixels',3840,'heightPixels',2160,
      'panel','IPS','refreshRateHz',60,'ports',JSON_ARRAY('HDMI','DisplayPort','USB-C'))
  ELSE
    JSON_OBJECT('brand','Lumen','screenSizeInches',34,'widthPixels',3440,'heightPixels',1440,
      'panel','VA','refreshRateHz',144,'ports',JSON_ARRAY('HDMI','DisplayPort'))
  END,
  sku.specifications=JSON_OBJECT('package','standard'),
  sku.spec_key=SHA2('{"package":"standard"}',256),
  sku.business_version=sku.business_version+1
WHERE p.create_request_id IN (
  'f784543a-5123-432b-9030-000000000001','f784543a-5123-432b-9030-000000000002',
  'f784543a-5123-432b-9030-000000000003','f784543a-5123-432b-9030-000000000004'
);

-- 两条搜索路径共用同一份规范化商品属性。
CREATE VIEW catalog_monitor_attribute_values AS
SELECT p.id AS product_id, a.name, a.value
FROM products p
JOIN JSON_TABLE(
  JSON_ARRAY(
    JSON_ARRAY('brand', LOWER(p.monitor_profile->>'$.brand')),
    JSON_ARRAY('screen size', TRIM(TRAILING '.' FROM
      TRIM(TRAILING '0' FROM CAST(CAST(p.monitor_profile->>'$.screenSizeInches' AS DECIMAL(5,1)) AS CHAR)))),
    JSON_ARRAY('resolution', CONCAT(p.monitor_profile->>'$.widthPixels', ' × ', p.monitor_profile->>'$.heightPixels')),
    JSON_ARRAY('panel', LOWER(p.monitor_profile->>'$.panel')),
    JSON_ARRAY('refresh rate', p.monitor_profile->>'$.refreshRateHz')
  ),
  '$[*]' COLUMNS (name VARCHAR(32) PATH '$[0]', value VARCHAR(64) PATH '$[1]')
) a
WHERE p.monitor_profile IS NOT NULL
UNION ALL
SELECT p.id, 'port', LOWER(port.value)
FROM products p
JOIN JSON_TABLE(p.monitor_profile, '$.ports[*]' COLUMNS (value VARCHAR(24) PATH '$')) port;

UPDATE catalog_search_state SET rebuild_requested=TRUE WHERE singleton_id=1;
