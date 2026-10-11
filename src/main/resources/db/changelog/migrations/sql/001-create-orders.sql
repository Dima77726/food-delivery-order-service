-- Корзины, заказы, история заказов, доставки, журнал аудита заказов, outbox и inbox —
-- в собственной базе сервиса заказов.
--
-- Итог миграций 005–028 монолита в одной. Типы колонок совпадают с монолитными один в один:
-- данные переносятся оттуда одноразовой задачей (docker/migrations/orders-from-monolith.sh)
-- с сохранением идентификаторов — на заказы ссылаются платежи, отзывы и трек курьера.
--
-- Внешних ключей на рестораны, блюда и пользователей нет: они живут в каталоге
-- и в identity-service. Название и цена блюда копируются в корзину и заказ в момент
-- добавления — заказ помнит, что и почём купил клиент, даже если меню потом изменится.
--
-- Отличия от схемы монолита — только то, что появилось вместе с сагой оплаты:
--   - статус заказа PAYMENT_PENDING и событие ORDER_PAYMENT_REQUESTED;
--   - способ оплаты и ключ идемпотентности запроса на оплату — в самом заказе;
--   - у outbox колонка topic: кроме событий заказа через него уходят команды сервису платежей;
--   - inbox (processed_message) — входящие сообщения, которые уже обработаны.

CREATE TABLE IF NOT EXISTS cart
(
    id            BIGSERIAL PRIMARY KEY,
    customer_id   BIGINT      NOT NULL,
    restaurant_id BIGINT      NOT NULL,
    status        VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_cart_status
        CHECK (status IN ('ACTIVE', 'CHECKED_OUT', 'CANCELED', 'ABANDONED'))
);

COMMENT ON TABLE cart IS 'Корзина клиента в выбранном ресторане';

CREATE INDEX IF NOT EXISTS idx_cart_customer_id ON cart (customer_id);
CREATE INDEX IF NOT EXISTS idx_cart_restaurant_id ON cart (restaurant_id);
CREATE INDEX IF NOT EXISTS idx_cart_status ON cart (status);

-- Одна активная корзина на пару «клиент — ресторан».
CREATE UNIQUE INDEX IF NOT EXISTS uq_cart_active_customer_restaurant
    ON cart (customer_id, restaurant_id)
    WHERE status = 'ACTIVE';

CREATE TABLE IF NOT EXISTS cart_item
(
    id             BIGSERIAL PRIMARY KEY,
    cart_id        BIGINT         NOT NULL,
    menu_item_id   BIGINT         NOT NULL,
    menu_item_name VARCHAR(255)   NOT NULL,
    quantity       INTEGER        NOT NULL,
    price          NUMERIC(10, 2) NOT NULL,
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMPTZ    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_cart_item_cart
        FOREIGN KEY (cart_id)
            REFERENCES cart (id)
            ON DELETE CASCADE,

    CONSTRAINT chk_cart_item_quantity_pozitive
        CHECK (quantity > 0),

    CONSTRAINT chk_cart_item_price_non_negative
        CHECK (price >= 0),

    CONSTRAINT uq_cart_item_cart_menu_item
        UNIQUE (cart_id, menu_item_id)
);

COMMENT ON TABLE cart_item IS 'Позиции корзины';
COMMENT ON COLUMN cart_item.menu_item_name IS 'Название блюда на момент добавления в корзину; меню живёт в catalog-service';
COMMENT ON COLUMN cart_item.price IS 'Цена блюда на момент добавления в корзину';

CREATE INDEX IF NOT EXISTS idx_cart_item_cart_id ON cart_item (cart_id);
CREATE INDEX IF NOT EXISTS idx_cart_item_menu_item_id ON cart_item (menu_item_id);

CREATE TABLE IF NOT EXISTS customer_order
(
    id                      BIGSERIAL PRIMARY KEY,
    cart_id                 BIGINT         NOT NULL,
    customer_id             BIGINT         NOT NULL,
    restaurant_id           BIGINT         NOT NULL,
    status                  VARCHAR(50)    NOT NULL DEFAULT 'CREATED',
    total_amount            NUMERIC(12, 2) NOT NULL,
    payment_method          VARCHAR(50),
    payment_idempotency_key VARCHAR(100),
    created_at              TIMESTAMPTZ    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMPTZ    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_customer_order_cart
        FOREIGN KEY (cart_id)
            REFERENCES cart (id)
            ON DELETE RESTRICT,

    CONSTRAINT uq_customer_order_cart
        UNIQUE (cart_id),

    CONSTRAINT chk_customer_order_status
        CHECK (status IN (
                          'CREATED',
                          'PAYMENT_PENDING',
                          'PAID',
                          'ACCEPTED',
                          'COOKING',
                          'READY_FOR_DELIVERY',
                          'IN_DELIVERY',
                          'DELIVERED',
                          'CANCELED'
            )),

    CONSTRAINT chk_customer_order_payment_method
        CHECK (payment_method IS NULL OR payment_method IN ('CARD', 'CASH_ON_DELIVERY')),

    CONSTRAINT chk_customer_order_total_amount_non_negative
        CHECK (total_amount >= 0)
);

COMMENT ON TABLE customer_order IS 'Заказ клиента, созданный из активной корзины';
COMMENT ON COLUMN customer_order.payment_method IS 'Способ оплаты, выбранный клиентом; пусто, пока оплату не запросили';
COMMENT ON COLUMN customer_order.payment_idempotency_key IS 'Ключ идемпотентности запроса на оплату; повтор с ним же не запускает вторую оплату';

CREATE INDEX IF NOT EXISTS idx_customer_order_customer_id ON customer_order (customer_id);
CREATE INDEX IF NOT EXISTS idx_customer_order_restaurant_id ON customer_order (restaurant_id);
CREATE INDEX IF NOT EXISTS idx_customer_order_status ON customer_order (status);

-- Ленты заказов курсором: см. findSummaries*After в OrderRepository.
CREATE INDEX IF NOT EXISTS idx_customer_order_customer_created_at_id
    ON customer_order (customer_id, created_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_customer_order_restaurant_created_at_id
    ON customer_order (restaurant_id, created_at DESC, id DESC);

-- Один ключ — один заказ: повтор ключа на другом заказе — ошибка клиента, а не новая оплата.
CREATE UNIQUE INDEX IF NOT EXISTS uq_customer_order_payment_idempotency_key
    ON customer_order (payment_idempotency_key)
    WHERE payment_idempotency_key IS NOT NULL;

-- Поиск заказов, не дождавшихся оплаты: см. PaymentTimeoutJob.
CREATE INDEX IF NOT EXISTS idx_customer_order_awaiting_payment
    ON customer_order (updated_at)
    WHERE status IN ('CREATED', 'PAYMENT_PENDING');

CREATE TABLE IF NOT EXISTS customer_order_item
(
    id             BIGSERIAL PRIMARY KEY,
    order_id       BIGINT         NOT NULL,
    menu_item_id   BIGINT         NOT NULL,
    menu_item_name VARCHAR(255)   NOT NULL,
    quantity       INTEGER        NOT NULL,
    price          NUMERIC(10, 2) NOT NULL,
    line_total     NUMERIC(12, 2) NOT NULL,
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_customer_order_item_order
        FOREIGN KEY (order_id)
            REFERENCES customer_order (id)
            ON DELETE CASCADE,

    CONSTRAINT chk_customer_order_item_quantity_positive
        CHECK (quantity > 0),

    CONSTRAINT chk_customer_order_item_price_non_negative
        CHECK (price >= 0),

    CONSTRAINT chk_customer_order_item_line_total_non_negative
        CHECK (line_total >= 0),

    CONSTRAINT uq_customer_order_item_order_menu_item
        UNIQUE (order_id, menu_item_id)
);

COMMENT ON TABLE customer_order_item IS 'Позиции заказа: название и цена блюда на момент оформления';

CREATE INDEX IF NOT EXISTS idx_customer_order_item_order_id ON customer_order_item (order_id);
CREATE INDEX IF NOT EXISTS idx_customer_order_item_menu_item_id ON customer_order_item (menu_item_id);

CREATE TABLE IF NOT EXISTS customer_order_event
(
    id          BIGSERIAL PRIMARY KEY,
    order_id    BIGINT       NOT NULL,
    event_type  VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_customer_order_event_order
        FOREIGN KEY (order_id)
            REFERENCES customer_order (id)
            ON DELETE CASCADE,

    CONSTRAINT chk_customer_order_event_type
        CHECK (event_type IN (
                              'ORDER_CREATED',
                              'ORDER_PAYMENT_REQUESTED',
                              'ORDER_PAID',
                              'ORDER_PAYMENT_FAILED',
                              'ORDER_REFUNDED',
                              'ORDER_CANCELED',
                              'ORDER_ACCEPTED',
                              'ORDER_COOKING_STARTED',
                              'ORDER_READY_FOR_DELIVERY',
                              'ORDER_PICKED_UP_BY_COURIER',
                              'ORDER_DELIVERED'
            ))
);

COMMENT ON TABLE customer_order_event IS 'История событий заказа';

CREATE INDEX IF NOT EXISTS idx_customer_order_event_order_id ON customer_order_event (order_id);
CREATE INDEX IF NOT EXISTS idx_customer_order_event_event_type ON customer_order_event (event_type);
CREATE INDEX IF NOT EXISTS idx_customer_order_event_created_at ON customer_order_event (created_at);

CREATE TABLE IF NOT EXISTS delivery
(
    id           BIGSERIAL PRIMARY KEY,
    order_id     BIGINT      NOT NULL,
    courier_id   BIGINT,
    status       VARCHAR(50) NOT NULL DEFAULT 'CREATED',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    picked_up_at TIMESTAMPTZ,
    delivered_at TIMESTAMPTZ,

    CONSTRAINT fk_delivery_order
        FOREIGN KEY (order_id)
            REFERENCES customer_order (id)
            ON DELETE RESTRICT,

    CONSTRAINT uq_delivery_order
        UNIQUE (order_id),

    CONSTRAINT chk_delivery_status
        CHECK (status IN ('CREATED', 'ASSIGNED', 'PICKED_UP', 'DELIVERED', 'CANCELED'))
);

COMMENT ON TABLE delivery IS 'Доставка заказа клиента';

CREATE INDEX IF NOT EXISTS idx_delivery_courier_id ON delivery (courier_id);
CREATE INDEX IF NOT EXISTS idx_delivery_status ON delivery (status);
CREATE INDEX IF NOT EXISTS idx_delivery_created_at ON delivery (created_at);

-- Журнал действий с заказами. Схема та же, что у журнала монолита и identity-service.
CREATE TABLE IF NOT EXISTS audit_log
(
    id             BIGSERIAL PRIMARY KEY,
    actor_id       BIGINT,
    actor_email    VARCHAR(255),
    action         VARCHAR(100) NOT NULL,
    resource_type  VARCHAR(100) NOT NULL,
    resource_id    VARCHAR(100),
    outcome        VARCHAR(50)  NOT NULL,
    details        VARCHAR(2000),
    correlation_id VARCHAR(64),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_audit_log_outcome
        CHECK (outcome IN ('SUCCESS', 'FAILURE'))
);

COMMENT ON TABLE audit_log IS 'Журнал действий с заказами; только на запись и чтение';

CREATE INDEX IF NOT EXISTS idx_audit_log_actor_id ON audit_log (actor_id);
CREATE INDEX IF NOT EXISTS idx_audit_log_created_at ON audit_log (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_log_resource ON audit_log (resource_type, resource_id);
CREATE INDEX IF NOT EXISTS idx_audit_log_correlation_id
    ON audit_log (correlation_id)
    WHERE correlation_id IS NOT NULL;

-- Outbox: сообщения, записанные в транзакции бизнес-операции и ещё не отправленные в Kafka.
CREATE TABLE IF NOT EXISTS order_event_outbox
(
    id             BIGSERIAL PRIMARY KEY,
    event_id       UUID         NOT NULL,
    topic          VARCHAR(200) NOT NULL,
    aggregate_type VARCHAR(50)  NOT NULL,
    aggregate_id   BIGINT       NOT NULL,
    event_type     VARCHAR(100) NOT NULL,
    payload        TEXT         NOT NULL,
    status         VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    attempts       INTEGER      NOT NULL DEFAULT 0,
    last_error     VARCHAR(1000),
    correlation_id VARCHAR(64),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at   TIMESTAMPTZ,

    CONSTRAINT uq_order_event_outbox_event_id
        UNIQUE (event_id),

    CONSTRAINT chk_order_event_outbox_status
        CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED'))
);

COMMENT ON TABLE order_event_outbox IS 'Очередь сообщений на публикацию в Kafka: события заказа и команды сервису платежей';
COMMENT ON COLUMN order_event_outbox.topic IS 'Топик Kafka, куда уйдёт сообщение';
COMMENT ON COLUMN order_event_outbox.aggregate_id IS 'Идентификатор заказа; он же ключ сообщения, чтобы сообщения одного заказа шли по порядку';

CREATE INDEX IF NOT EXISTS idx_order_event_outbox_pending
    ON order_event_outbox (created_at, id)
    WHERE status = 'PENDING';

CREATE INDEX IF NOT EXISTS idx_order_event_outbox_failed
    ON order_event_outbox (id)
    WHERE status = 'FAILED';

CREATE INDEX IF NOT EXISTS idx_order_event_outbox_aggregate
    ON order_event_outbox (aggregate_type, aggregate_id);

-- Inbox: идентификаторы входящих сообщений, которые уже обработаны. Kafka доставляет
-- «хотя бы один раз», и повтор события «оплата прошла» не должен второй раз двигать заказ.
CREATE TABLE IF NOT EXISTS processed_message
(
    message_id   UUID PRIMARY KEY,
    topic        VARCHAR(200) NOT NULL,
    processed_at TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE processed_message IS 'Обработанные входящие сообщения Kafka; защита от повторной доставки';
