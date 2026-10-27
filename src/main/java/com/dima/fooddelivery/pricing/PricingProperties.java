package com.dima.fooddelivery.pricing;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * Цены и правила оформления.
 *
 * @param baseDeliveryFee  цена доставки при нормальном спросе
 * @param maxSurge         потолок коэффициента спроса: даже в час пик доставка дороже не более чем вдвое
 * @param surgeInterval    как часто пересчитывается коэффициент
 * @param maxTip           потолок чаевых — защита от опечатки «3000» вместо «300»
 * @param scheduleMinLead  заказ ко времени — не раньше чем через столько от оформления
 * @param scheduleMaxAhead и не дальше этого
 * @param acceptWindow     ресторан может принять заказ ко времени не раньше чем за столько до срока
 */
@ConfigurationProperties(prefix = "app.pricing")
public record PricingProperties(
        @DefaultValue("149") BigDecimal baseDeliveryFee,
        @DefaultValue("2.0") BigDecimal maxSurge,
        @DefaultValue("PT30S") Duration surgeInterval,
        @DefaultValue("3000") BigDecimal maxTip,
        @DefaultValue("PT45M") Duration scheduleMinLead,
        @DefaultValue("P7D") Duration scheduleMaxAhead,
        @DefaultValue("PT60M") Duration acceptWindow
) {
}
