package ru.leyman.thoth.api.dto;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Доля гостя по позиции.
 * shareRatio округляется до 2 знаков (HALF_UP) и проверяется на 0..1 в сервисе.
 * Не указан — гость добавляется к позиции с долей 0.
 */
public record AssignRequest(
        @NotNull Long itemId,
        @NotNull Long guestId,
        BigDecimal shareRatio
) {
}
