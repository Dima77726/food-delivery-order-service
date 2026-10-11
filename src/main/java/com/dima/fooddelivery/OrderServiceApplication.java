package com.dima.fooddelivery;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Сервис заказов: корзина, заказ, доставка и оркестратор саги оплаты.
 *
 * <p>Класс в корневом пакете по той же причине, что у каталога и identity-service: модули
 * {@code cart}, {@code order}, {@code delivery} переехали из монолита со своими пакетами,
 * и сканирование от корня находит их без правок.
 *
 * <p>{@code @EnableScheduling} нужен фоновым задачам: публикации outbox и отмене заказов,
 * не дождавшихся оплаты.
 */
@EnableScheduling
@SpringBootApplication
public class OrderServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
