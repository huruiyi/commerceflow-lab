CREATE TABLE inventory_warehouses (
  id BIGINT NOT NULL PRIMARY KEY COMMENT '稳定仓库编号',
  code VARCHAR(40) NOT NULL UNIQUE COMMENT '仓库业务代码',
  priority INT NOT NULL COMMENT '选仓优先级，较小值优先',
  enabled BOOLEAN NOT NULL COMMENT '是否受理新预占'
);

INSERT INTO inventory_warehouses (id,code,priority,enabled)
VALUES (1,'DEFAULT',0,TRUE),(2,'SECONDARY',10,TRUE);

CREATE TABLE inventory_warehouse_stock (
  product_id BIGINT NOT NULL COMMENT '商品编号',
  warehouse_id BIGINT NOT NULL COMMENT '库存所在仓库',
  stock_available INT NOT NULL COMMENT '可分配库存',
  stock_reserved INT NOT NULL COMMENT '等待付款的预占件数',
  stock_sold INT NOT NULL COMMENT '已提交的库存件数',
  PRIMARY KEY (product_id,warehouse_id),
  CONSTRAINT fk_warehouse_stock_product FOREIGN KEY (product_id) REFERENCES product_inventory(product_id),
  CONSTRAINT fk_warehouse_stock_warehouse FOREIGN KEY (warehouse_id) REFERENCES inventory_warehouses(id),
  CHECK (stock_available >= 0 AND stock_reserved >= 0 AND stock_sold >= 0)
);

INSERT INTO inventory_warehouse_stock (product_id,warehouse_id,stock_available,stock_reserved,stock_sold)
SELECT product_id,1,stock_available,stock_reserved,stock_sold FROM product_inventory;

ALTER TABLE inventory_reservations
  ADD COLUMN warehouse_id BIGINT NOT NULL DEFAULT 1 COMMENT '初次分配时锁定的发货仓',
  DROP INDEX uk_inventory_reservation_order_product,
  ADD UNIQUE KEY uk_inventory_reservation_order_product_warehouse(order_id,product_id,warehouse_id),
  ADD CONSTRAINT fk_reservation_warehouse FOREIGN KEY (warehouse_id) REFERENCES inventory_warehouses(id);

ALTER TABLE inventory_movements
  ADD COLUMN warehouse_id BIGINT NOT NULL DEFAULT 1 COMMENT '流水归属仓，历史流水归入默认仓';
