-- Фаза 4: промокоды, динамическая цена доставки, чаевые, заказ ко времени, возвраты, автоназначение курьера.

-- 1. Условия заказа: из чего сложилась сумма и к какому времени его ждут. Отдельная таблица, а не
-- колонки customer_order: итог по-прежнему в customer_order.total_amount — его списывает сага оплаты и
-- его же читают остальные сервисы, — а расшифровка нужна только экрану заказа, расчётам и курьеру.
CREATE TABLE IF NOT EXISTS order_terms
(
    order_id         BIGINT PRIMARY KEY REFERENCES customer_order (id) ON DELETE CASCADE,
    items_total      NUMERIC(12, 2) NOT NULL,
    promo_code       VARCHAR(40),
    discount         NUMERIC(12, 2) NOT NULL DEFAULT 0,
    delivery_fee     NUMERIC(12, 2) NOT NULL DEFAULT 0,
    surge_multiplier NUMERIC(4, 2)  NOT NULL DEFAULT 1.00,
    tip_amount       NUMERIC(12, 2) NOT NULL DEFAULT 0,
    scheduled_for    TIMESTAMPTZ,
    prep_reminded_at TIMESTAMPTZ
);

-- Заказы ко времени, которым ещё не напомнили «пора готовить»: частичный индекс по одним им.
CREATE INDEX IF NOT EXISTS idx_order_terms_scheduled
    ON order_terms (scheduled_for) WHERE scheduled_for IS NOT NULL AND prep_reminded_at IS NULL;

-- 2. Промокоды. used_count растёт атомарным UPDATE с условием used_count < max_uses: два клиента,
-- одновременно применившие последний экземпляр, не получат оба — второй UPDATE затронет ноль строк.
CREATE TABLE IF NOT EXISTS promo_code
(
    code                VARCHAR(40) PRIMARY KEY,
    discount_type       VARCHAR(10)    NOT NULL,
    discount_value      NUMERIC(12, 2) NOT NULL,
    max_discount        NUMERIC(12, 2),
    min_order_amount    NUMERIC(12, 2) NOT NULL DEFAULT 0,
    restaurant_id       BIGINT,
    max_uses            INT            NOT NULL,
    used_count          INT            NOT NULL DEFAULT 0,
    valid_from          TIMESTAMPTZ    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    valid_until         TIMESTAMPTZ,
    active              BOOLEAN        NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_promo_type CHECK (discount_type IN ('PERCENT', 'FIXED')),
    CONSTRAINT chk_promo_uses CHECK (used_count <= max_uses)
);

-- Один промокод — один раз на клиента: уникальный индекс, а не проверка перед вставкой.
CREATE TABLE IF NOT EXISTS promo_redemption
(
    code        VARCHAR(40) NOT NULL REFERENCES promo_code (code),
    customer_id BIGINT      NOT NULL,
    order_id    BIGINT      NOT NULL REFERENCES customer_order (id) ON DELETE CASCADE,
    redeemed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_promo_redemption PRIMARY KEY (code, customer_id)
);

-- 3. Заявки на возврат по позициям доставленного заказа («не привезли напиток»).
CREATE TABLE IF NOT EXISTS refund_request
(
    id           BIGSERIAL PRIMARY KEY,
    order_id     BIGINT         NOT NULL REFERENCES customer_order (id),
    customer_id  BIGINT         NOT NULL,
    amount       NUMERIC(12, 2) NOT NULL,
    items        TEXT           NOT NULL,
    reason       VARCHAR(500)   NOT NULL,
    status       VARCHAR(20)    NOT NULL DEFAULT 'PENDING',
    decided_by   BIGINT,
    decided_at   TIMESTAMPTZ,
    command_id   UUID           NOT NULL,
    created_at   TIMESTAMPTZ    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_refund_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED'))
);

CREATE INDEX IF NOT EXISTS idx_refund_request_order ON refund_request (order_id);
CREATE INDEX IF NOT EXISTS idx_refund_request_pending ON refund_request (created_at) WHERE status = 'PENDING';

-- 4. Предложения доставки курьерам при автоназначении: кому, когда истекает, чем кончилось.
CREATE TABLE IF NOT EXISTS delivery_offer
(
    id          BIGSERIAL PRIMARY KEY,
    delivery_id BIGINT      NOT NULL REFERENCES delivery (id) ON DELETE CASCADE,
    courier_id  BIGINT      NOT NULL,
    distance_m  INT,
    status      VARCHAR(10) NOT NULL DEFAULT 'OFFERED',
    expires_at  TIMESTAMPTZ NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    decided_at  TIMESTAMPTZ,

    CONSTRAINT chk_offer_status CHECK (status IN ('OFFERED', 'ACCEPTED', 'DECLINED', 'EXPIRED', 'CANCELED'))
);

-- Одно действующее предложение на доставку: второе, вставленное параллельно, нарушит индекс.
CREATE UNIQUE INDEX IF NOT EXISTS uq_delivery_offer_active ON delivery_offer (delivery_id) WHERE status = 'OFFERED';
CREATE INDEX IF NOT EXISTS idx_delivery_offer_courier ON delivery_offer (courier_id) WHERE status = 'OFFERED';
CREATE INDEX IF NOT EXISTS idx_delivery_offer_expiry ON delivery_offer (expires_at) WHERE status = 'OFFERED';

-- 5. ShedLock: распределённая блокировка фоновых задач. Задачу по расписанию запускают все экземпляры,
-- выполняет тот, кто первым занял строку блокировки на lock_until.
CREATE TABLE IF NOT EXISTS shedlock
(
    name       VARCHAR(64)  PRIMARY KEY,
    lock_until TIMESTAMPTZ  NOT NULL,
    locked_at  TIMESTAMPTZ  NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);

COMMENT ON TABLE order_terms IS 'Расшифровка суммы заказа и время доставки ко времени';
COMMENT ON TABLE promo_code IS 'Промокоды: скидка, лимит использований, срок действия';
COMMENT ON TABLE refund_request IS 'Заявки на частичный возврат по позициям доставленного заказа';
COMMENT ON TABLE delivery_offer IS 'Предложения доставки курьерам при автоназначении';
COMMENT ON TABLE shedlock IS 'Распределённые блокировки фоновых задач (ShedLock)';
