package ru.leyman.thoth.api.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Финальный расчёт: кто сколько платит.
 * Сумма guests[].total всегда ровно assignedTotal.
 *
 * @param complete           все позиции назначены
 * @param unassignedItemIds  позиции, которые ещё никому не назначены
 * @param roundingAdjustment сколько добавлено гостю с наибольшей долей, чтобы итоги сошлись с чеком
 */
public record SplitSummaryResponse(
        String currency,
        BigDecimal receiptTotal,
        BigDecimal assignedTotal,
        BigDecimal unassignedTotal,
        boolean complete,
        List<Long> unassignedItemIds,
        BigDecimal roundingAdjustment,
        Long roundingGuestId,
        List<GuestTotal> guests
) {
    public record GuestTotal(Long guestId, String name, BigDecimal total) {
    }
}
