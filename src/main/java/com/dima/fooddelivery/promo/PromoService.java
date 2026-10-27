package com.dima.fooddelivery.promo;

import com.dima.fooddelivery.common.exception.BusinessRuleViolationException;
import com.dima.fooddelivery.promo.PromoRepository.PromoCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;

/**
 * Промокоды: проверка («сколько скинет») и погашение при оформлении заказа.
 *
 * <p>Порядок проверок — от дешёвой к дорогой: фильтр Блума в памяти → строка кода → правила →
 * атомарное занятие использования. Погашение идёт в транзакции создания заказа: заказ откатился —
 * откатилось и использование.
 */
@Slf4j
@Service
public class PromoService {

    private final PromoRepository repository;
    private final PromoBloomFilter filter;

    public PromoService(PromoRepository repository, PromoBloomFilter filter) {
        this.repository = repository;
        this.filter = filter;
    }

    /** Перестройка — на старте и раз в час: из фильтра Блума нельзя удалять, отключённые коды копятся. */
    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1H")
    public void rebuildFilter() {
        filter.rebuild(repository.allCodes());
    }

    public static String normalize(String code) {
        return code == null ? null : code.trim().toUpperCase(Locale.ROOT);
    }

    /** Размер скидки без погашения — для корзины. @throws BusinessRuleViolationException с причиной */
    @Transactional(readOnly = true)
    public BigDecimal preview(String rawCode, long customerId, long restaurantId, BigDecimal itemsTotal) {
        PromoCode promo = requireApplicable(normalize(rawCode), customerId, restaurantId, itemsTotal);
        return discountOf(promo, itemsTotal);
    }

    /** Погасить в транзакции заказа. @return скидка */
    @Transactional
    public BigDecimal redeem(String rawCode, long customerId, long restaurantId, BigDecimal itemsTotal, long orderId) {
        String code = normalize(rawCode);
        PromoCode promo = requireApplicable(code, customerId, restaurantId, itemsTotal);
        if (!repository.tryConsume(code)) {
            throw new BusinessRuleViolationException("Промокод " + code + " закончился");
        }
        try {
            repository.insertRedemption(code, customerId, orderId);
        } catch (DuplicateKeyException exception) {
            // Два одновременных заказа одного клиента с одним кодом: второй упирается в первичный ключ.
            throw new BusinessRuleViolationException("Промокод " + code + " уже использован");
        }
        log.info("Промокод погашен: code={}, customerId={}, orderId={}", code, customerId, orderId);
        return discountOf(promo, itemsTotal);
    }

    public void create(PromoCode promo) {
        repository.insert(promo);
        if (!filter.add(promo.code())) {
            rebuildFilter();
        }
    }

    public List<PromoCode> list() {
        return repository.findAll();
    }

    public void deactivate(String code) {
        repository.deactivate(normalize(code));
    }

    private PromoCode requireApplicable(String code, long customerId, long restaurantId, BigDecimal itemsTotal) {
        if (code == null || code.isBlank() || !filter.mightContain(code)) {
            throw new BusinessRuleViolationException("Промокод не найден");
        }
        PromoCode promo = repository.find(code).orElseThrow(() -> new BusinessRuleViolationException("Промокод не найден"));
        OffsetDateTime now = OffsetDateTime.now();
        if (!promo.active() || promo.validFrom().isAfter(now) || (promo.validUntil() != null && !promo.validUntil().isAfter(now))) {
            throw new BusinessRuleViolationException("Промокод " + code + " сейчас не действует");
        }
        if (promo.usedCount() >= promo.maxUses()) {
            throw new BusinessRuleViolationException("Промокод " + code + " закончился");
        }
        if (promo.restaurantId() != null && promo.restaurantId() != restaurantId) {
            throw new BusinessRuleViolationException("Промокод " + code + " действует в другом ресторане");
        }
        if (itemsTotal.compareTo(promo.minOrderAmount()) < 0) {
            throw new BusinessRuleViolationException("Промокод " + code + " — от " + promo.minOrderAmount().toPlainString() + " ₽");
        }
        if (repository.usedBy(code, customerId)) {
            throw new BusinessRuleViolationException("Промокод " + code + " уже использован");
        }
        return promo;
    }

    /** Скидка не больше суммы блюд и не больше потолка; процент — до копейки вниз. */
    static BigDecimal discountOf(PromoCode promo, BigDecimal itemsTotal) {
        BigDecimal discount = promo.type() == PromoCode.Type.PERCENT
                ? itemsTotal.multiply(promo.value()).divide(BigDecimal.valueOf(100), 2, RoundingMode.DOWN)
                : promo.value();
        if (promo.maxDiscount() != null) {
            discount = discount.min(promo.maxDiscount());
        }
        return discount.min(itemsTotal).setScale(2, RoundingMode.DOWN);
    }
}
