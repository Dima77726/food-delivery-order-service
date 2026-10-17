-- Адрес доставки заказа: строка адреса, уточнение (квартира, подъезд, домофон) и точка на карте.
-- Колонки допускают NULL: у заказов, созданных до появления адреса, его нет. Координаты либо обе
-- заданы, либо обе пусты: точка с одной координатой бессмысленна.
ALTER TABLE customer_order ADD COLUMN IF NOT EXISTS delivery_address VARCHAR(500);
ALTER TABLE customer_order ADD COLUMN IF NOT EXISTS delivery_address_details VARCHAR(200);
ALTER TABLE customer_order ADD COLUMN IF NOT EXISTS delivery_latitude NUMERIC(9, 6);
ALTER TABLE customer_order ADD COLUMN IF NOT EXISTS delivery_longitude NUMERIC(9, 6);

ALTER TABLE customer_order ADD CONSTRAINT chk_customer_order_delivery_point
    CHECK ((delivery_latitude IS NULL) = (delivery_longitude IS NULL)
        AND (delivery_latitude IS NULL OR delivery_latitude BETWEEN -90 AND 90)
        AND (delivery_longitude IS NULL OR delivery_longitude BETWEEN -180 AND 180));
