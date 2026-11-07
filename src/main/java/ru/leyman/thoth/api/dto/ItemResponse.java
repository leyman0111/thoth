package ru.leyman.thoth.api.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Позиция чека. У назначенной позиции сумма долей всегда ровно 1.00.
 *
 * @param assignments доли в порядке добавления гостей на позицию (в том числе нулевые)
 */
public record ItemResponse(
        Long id,
        String name,
        BigDecimal price,
        String currency,
        Status status,
        List<Assignment> assignments
) {
    public enum Status {UNASSIGNED, ASSIGNED}

    /** amount — цена × доля с округлением до копеек, для отображения. Итог гостя считается в summary. */
    public record Assignment(Long guestId, String guestName, BigDecimal shareRatio, BigDecimal amount) {
    }
}
