package ru.leyman.thoth.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import ru.leyman.thoth.service.BillSplitterService.ItemLine;
import ru.leyman.thoth.service.BillSplitterService.Share;

import java.math.BigDecimal;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BillSplitterCalculationTest {

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    @ParameterizedTest
    @CsvSource({"0.705, 0.71", "0.704, 0.70", "0.333, 0.33", "1.004, 1.00", "-0.004, 0.00", "0, 0.00"})
    void rule7_roundsHalfUp(String input, String expected) {
        assertThat(BillSplitterService.normalizeShare(d(input))).isEqualTo(d(expected));
    }

    @ParameterizedTest
    @CsvSource({"-0.01", "1.01", "1.005", "2"})
    void rule7_rejectsOutOfRange(String input) {
        assertThatThrownBy(() -> BillSplitterService.normalizeShare(d(input)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatusCode().value()).isEqualTo(400));
    }

    @Test
    void rule8_sumOfPriceTimesShare() {
        var split = BillSplitterService.calculate(
                List.of(new ItemLine(1, d("1000.00")), new ItemLine(2, d("300.00"))),
                List.of(10L, 20L),
                List.of(new Share(1, 10, d("0.70")), new Share(1, 20, d("0.30")), new Share(2, 20, d("1.00"))));

        assertThat(split.guestTotals()).containsEntry(10L, d("700.00")).containsEntry(20L, d("600.00"));
        assertThat(split.roundingDiff()).isEqualByComparingTo("0");
        assertThat(split.roundingGuestId()).isNull();
        assertThat(split.complete()).isTrue();
    }

    @Test
    void rule9_extraKopeckIsTakenFromGuestWithLargestShare() {
        // 0.05 × 0.5 = 0.025 → оба округляются до 0.03, итого 0.06 вместо 0.05
        var split = BillSplitterService.calculate(
                List.of(new ItemLine(1, d("0.05")), new ItemLine(2, d("10.00"))),
                List.of(10L, 20L),
                List.of(new Share(1, 10, d("0.50")), new Share(1, 20, d("0.50")),
                        new Share(2, 20, d("1.00"))));

        assertThat(split.roundingDiff()).isEqualByComparingTo("-0.01");
        assertThat(split.roundingGuestId()).isEqualTo(20L);
        assertThat(split.guestTotals()).containsEntry(10L, d("0.03")).containsEntry(20L, d("10.02"));
    }

    @Test
    void rule9_missingKopeckIsAddedToGuestWithLargestShare() {
        // 100.01 × 0.10/0.41/0.49 = 10.001 + 41.0041 + 49.0049 → 10.00 + 41.00 + 49.00 = 100.00, не хватает 0.01
        var split = BillSplitterService.calculate(
                List.of(new ItemLine(1, d("100.01"))),
                List.of(1L, 2L, 3L),
                List.of(new Share(1, 1, d("0.10")), new Share(1, 2, d("0.41")), new Share(1, 3, d("0.49"))));

        assertThat(split.roundingDiff()).isEqualByComparingTo("0.01");
        assertThat(split.roundingGuestId()).isEqualTo(3L);
        assertThat(split.guestTotals()).containsEntry(1L, d("10.00"))
                .containsEntry(2L, d("41.00"))
                .containsEntry(3L, d("49.01"));
    }

    @Test
    void totalsAlwaysMatchReceiptOnRandomBills() {
        Random random = new Random(42);
        for (int run = 0; run < 2_000; run++) {
            int guests = 2 + random.nextInt(8);
            int items = 1 + random.nextInt(15);
            List<Long> guestIds = java.util.stream.LongStream.rangeClosed(1, guests).boxed().toList();
            List<ItemLine> lines = new java.util.ArrayList<>();
            List<Share> shares = new java.util.ArrayList<>();
            for (long item = 1; item <= items; item++) {
                lines.add(new ItemLine(item, BigDecimal.valueOf(1 + random.nextInt(500_000), 2)));
                // случайное разбиение 100 сотых между случайными гостями
                int left = 100;
                List<Long> takers = new java.util.ArrayList<>(guestIds);
                java.util.Collections.shuffle(takers, random);
                int count = 1 + random.nextInt(guests);
                for (int k = 0; k < count; k++) {
                    int part = k == count - 1 ? left : random.nextInt(left + 1);
                    left -= part;
                    shares.add(new Share(item, takers.get(k), BigDecimal.valueOf(part, 2)));
                }
            }
            var split = BillSplitterService.calculate(lines, guestIds, shares);
            assertThat(split.guestTotals().values().stream().reduce(BigDecimal.ZERO, BigDecimal::add))
                    .as("run %d", run)
                    .isEqualByComparingTo(split.receiptTotal());
            assertThat(split.roundingDiff().abs()).isLessThanOrEqualTo(BigDecimal.valueOf(guests, 2));
        }
    }

    @Test
    void unassignedItemsAreReportedSeparately() {
        var split = BillSplitterService.calculate(
                List.of(new ItemLine(1, d("500.00")), new ItemLine(2, d("120.00"))),
                List.of(10L),
                List.of(new Share(1, 10, d("1.00"))));

        assertThat(split.guestTotals()).containsEntry(10L, d("500.00"));
        assertThat(split.unassignedItems()).containsExactly(2L);
        assertThat(split.unassignedTotal()).isEqualByComparingTo("120.00");
        assertThat(split.complete()).isFalse();
    }
}
