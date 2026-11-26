INSERT INTO inventory_warehouses(id,code,priority,enabled)
VALUES (1,'DEFAULT',0,TRUE),(2,'SECONDARY',10,TRUE)
ON CONFLICT (id) DO NOTHING;
