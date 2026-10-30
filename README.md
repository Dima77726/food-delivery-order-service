# food-delivery-order-service

Сервис платформы доставки еды. Платформа целиком (docker-compose, наблюдаемость, Postman,
общая документация) — в репозитории [food-delivery-platform](https://github.com/Dima77726/food-delivery-platform);
контракты событий, gRPC и REST — в [food-delivery-contracts](https://github.com/Dima77726/food-delivery-contracts).

## Сборка

Контракты лежат в GitHub Packages, а он требует токен даже для чтения. Один раз в
`~/.m2/settings.xml`:

```xml
<servers>
  <server>
    <id>github</id>
    <username>ВАШ_GITHUB_ЛОГИН</username>
    <password>ТОКЕН_С_ПРАВОМ_read:packages</password>
  </server>
</servers>
```

```bash
./mvnw verify
```

Интеграционным тестам нужен Docker (Testcontainers).

## Образ

```bash
docker build --secret id=maven_settings,src=$HOME/.m2/settings.xml -t food-delivery-order-service .
```

CI публикует образ в `ghcr.io/<владелец>/food-delivery-order-service` на каждый пуш в `main` и `dev`.

## Заказ на Camunda 8

По умолчанию сагу оплаты ведёт сам сервис (`order/saga/PaymentSaga`, таймаут — `PaymentTimeoutJob`).
С `SAGA_ENGINE=camunda` и `CAMUNDA_CLIENT_ENABLED=true` заказ ведёт процесс
[`order-lifecycle`](src/main/resources/bpmn/order-lifecycle.bpmn) в Camunda 8 (пакет `order/saga/camunda`),
один экземпляр от запроса оплаты до доставки: исходы оплаты из Kafka и смены статусов становятся сообщениями
процесса, таймаут оплаты и SLA принятия рестораном (`ORDER_ACCEPTANCE_SLA`, 10 мин) — его таймерами, реакции —
обработчиками заданий. Сервис деплоит процесс сам при старте. Адреса брокера — `CAMUNDA_GRPC_ADDRESS`
(по умолчанию `http://localhost:26500`) и `CAMUNDA_REST_ADDRESS` (`http://localhost:8091`); в стеке
платформы Camunda поднимается профилем `bpm` (`./scripts/start.ps1 -Bpm`).

## Партнёрский SOAP API (заказы для касс)

Для касс ресторанов, которым REST неудобен. Контракт — XSD в `soap-contracts`
(`urn:fooddelivery:partner:v1`), WSDL строится по нему: `GET /ws/orders.wsdl` (без токена). Запросы —
`POST /ws/orders` с заголовком `Authorization: Bearer <JWT>`; права как у REST (управляющий рестораном
или администратор), заказ другого ресторана недоступен.

Операции: `ListOpenOrders` (оплачены, приняты, готовятся, ждут курьера; новые первыми),
`GetOrderStatus`, `AcceptOrder`. Правила переходов общие с REST — их выполняет `OrderService`.
Ошибки — SOAP Fault с деталью `PartnerFault` (`FORBIDDEN`, `NOT_FOUND`, `CONFLICT`, …).
