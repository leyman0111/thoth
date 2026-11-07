package ru.leyman.thoth.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Чистая логика перераспределения долей по одной позиции (без БД).
 * Вычисления в сотых долях (int), поэтому сумма у назначенной позиции всегда ровно 1.00.
 * <p>
 * Гости сессии фиксированы. Строки позиции идут в порядке, в котором гости на неё попали.
 *
 * <h2>Правила</h2>
 * <ol>
 *   <li><b>Первый гость на позиции</b> получает X; если X &lt; 1, остаток 1 − X получает
 *       следующий гость из списка сессии.</li>
 *   <li><b>Доля уменьшилась</b> — освободившееся получает следующий гость из списка сессии,
 *       которого ещё нет на позиции (новая строка в конце). Если свободных гостей нет —
 *       следующий на позиции, а если изменён последний — предыдущий.</li>
 *   <li><b>Доля увеличилась</b> — разница забирается у остальных на позиции, начиная с последнего;
 *       не хватает — у предыдущих.</li>
 *   <li><b>Гость добавлен к позиции, где уже есть другие</b> — получает X (по умолчанию 0),
 *       остальные пропорционально уменьшаются до 1 − X.</li>
 *   <li>Гость с долей 0 остаётся на позиции.</li>
 * </ol>
 * «Следующий гость из списка» — следующий после изменённого в порядке гостей сессии,
 * по кругу, из тех, кого ещё нет на позиции.
 */
public final class ShareRebalancer {

    private static final int WHOLE = 100;

    private ShareRebalancer() {
    }

    public record Row(long guestId, BigDecimal share) {
        public Row {
            share = share.setScale(2, RoundingMode.UNNECESSARY);
        }
    }

    /**
     * @param current       строки позиции сейчас (сумма 1.00 или пусто), в порядке добавления
     * @param sessionGuests id гостей сессии в их порядке
     * @param guestId       кому ставим долю
     * @param requested     доля, уже округлённая до 2 знаков и в диапазоне [0; 1]
     * @return новые строки позиции; добавленные гости — в конце
     */
    public static List<Row> setShare(List<Row> current, List<Long> sessionGuests, long guestId, BigDecimal requested) {
        List<MutableRow> rows = toMutable(current);
        int target = hundredths(requested);
        int index = indexOf(rows, guestId);

        if (rows.isEmpty()) {
            rows.add(new MutableRow(guestId, target));
            if (target < WHOLE) {
                Long next = nextFreeGuest(sessionGuests, rows, guestId);
                if (next == null) {
                    throw ApiException.conflict("Остаток " + BigDecimal.valueOf(WHOLE - target, 2)
                            + " некому отдать: в сессии нет других гостей");
                }
                rows.add(new MutableRow(next, WHOLE - target));
            }
        } else if (index < 0) {
            scaleProportionally(rows, WHOLE - target);
            rows.add(new MutableRow(guestId, target));
        } else {
            MutableRow edited = rows.get(index);
            int delta = target - edited.share;
            if (delta == 0) {
                return toRows(rows);
            }
            edited.share = target;
            if (delta < 0) {
                giveAway(rows, sessionGuests, index, -delta);
            } else {
                takeFromOthers(rows, index, delta);
            }
        }
        return toRows(rows);
    }

    /** Правило 2: освободившаяся доля — новому гостю из списка, иначе соседу по позиции. */
    private static void giveAway(List<MutableRow> rows, List<Long> sessionGuests, int index, int freed) {
        Long next = nextFreeGuest(sessionGuests, rows, rows.get(index).guestId);
        if (next != null) {
            rows.add(new MutableRow(next, freed));
            return;
        }
        if (rows.size() == 1) {
            throw ApiException.conflict("Остаток " + BigDecimal.valueOf(freed, 2)
                    + " некому отдать: в сессии нет других гостей");
        }
        int receiver = index + 1 < rows.size() ? index + 1 : index - 1;
        rows.get(receiver).share += freed;
    }

    /** Правило 3: разница забирается с конца позиции, пропуская изменённого гостя. */
    private static void takeFromOthers(List<MutableRow> rows, int index, int needed) {
        int remaining = needed;
        for (int i = rows.size() - 1; i >= 0 && remaining > 0; i--) {
            if (i == index) {
                continue;
            }
            MutableRow row = rows.get(i);
            int take = Math.min(remaining, row.share);
            row.share -= take;
            remaining -= take;
        }
        if (remaining > 0) {
            throw new IllegalStateException("Сумма долей по позиции нарушена: не хватает " + remaining + "/100");
        }
    }

    /** Следующий после guestId гость сессии (по кругу), которого ещё нет на позиции. */
    static Long nextFreeGuest(List<Long> sessionGuests, List<MutableRow> rows, long guestId) {
        Set<Long> onItem = rows.stream().map(r -> r.guestId).collect(Collectors.toSet());
        int n = sessionGuests.size();
        int start = sessionGuests.indexOf(guestId);
        for (int step = 1; step <= n; step++) {
            long candidate = sessionGuests.get(Math.floorMod(start + step, n));
            if (!onItem.contains(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** Правило 4: метод наибольшего остатка — новые доли пропорциональны старым и в сумме дают total. */
    private static void scaleProportionally(List<MutableRow> rows, int total) {
        int oldSum = rows.stream().mapToInt(r -> r.share).sum();
        int allocated = 0;
        double[] remainders = new double[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            double exact = (double) rows.get(i).share * total / oldSum;
            int floor = (int) Math.floor(exact);
            remainders[i] = exact - floor;
            rows.get(i).share = floor;
            allocated += floor;
        }
        List<Integer> byRemainder = IntStream.range(0, rows.size()).boxed()
                .sorted(Comparator.<Integer>comparingDouble(i -> remainders[i]).reversed())
                .toList();
        for (int k = 0; k < total - allocated; k++) {
            rows.get(byRemainder.get(k)).share++;
        }
    }

    private static List<MutableRow> toMutable(List<Row> current) {
        List<MutableRow> rows = new ArrayList<>(current.size());
        current.forEach(r -> rows.add(new MutableRow(r.guestId(), hundredths(r.share()))));
        checkSum(rows);
        return rows;
    }

    private static List<Row> toRows(List<MutableRow> rows) {
        checkSum(rows);
        return rows.stream().map(r -> new Row(r.guestId, BigDecimal.valueOf(r.share, 2))).toList();
    }

    private static void checkSum(List<MutableRow> rows) {
        int sum = rows.stream().mapToInt(r -> r.share).sum();
        if (!rows.isEmpty() && sum != WHOLE) {
            throw new IllegalStateException("Сумма долей по позиции " + sum + "/100 вместо 100/100");
        }
    }

    private static int indexOf(List<MutableRow> rows, long guestId) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).guestId == guestId) {
                return i;
            }
        }
        return -1;
    }

    static int hundredths(BigDecimal share) {
        return share.setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).intValueExact();
    }

    static final class MutableRow {
        final long guestId;
        int share;

        MutableRow(long guestId, int share) {
            this.guestId = guestId;
            this.share = share;
        }
    }
}
