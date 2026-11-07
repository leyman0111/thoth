package ru.leyman.thoth.openrouter;

import java.math.BigDecimal;
import java.util.List;

/**
 * Позиции чека, как их вернула модель (до проверки и конвертации валюты).
 *
 * @param items непустой список
 */
public record RecognizedReceipt(List<Line> items) {

    /**
     * @param currency как вернула модель; null, если не указана
     */
    public record Line(String name, BigDecimal price, String currency) {
    }
}
