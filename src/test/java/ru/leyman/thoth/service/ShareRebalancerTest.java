package ru.leyman.thoth.service;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import ru.leyman.thoth.service.ShareRebalancer.Row;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShareRebalancerTest {

    // Гости сессии в порядке добавления
    private static final long ANYA = 1, BORYA = 2, VIKA = 3, GOSHA = 4;
    private static final List<Long> THREE = List.of(ANYA, BORYA, VIKA);
    private static final List<Long> FOUR = List.of(ANYA, BORYA, VIKA, GOSHA);

    private static Row row(long guestId, String share) {
        return new Row(guestId, new BigDecimal(share));
    }

    private static List<Row> set(List<Row> rows, List<Long> guests, long guestId, String share) {
        return ShareRebalancer.setShare(rows, guests, guestId, new BigDecimal(share));
    }

    @Nested
    class FirstGuestOnItem {
        @Test
        void remainderGoesToNextGuestFromList() {
            assertThat(set(List.of(), THREE, ANYA, "0.70"))
                    .containsExactly(row(ANYA, "0.70"), row(BORYA, "0.30"));
        }

        @Test
        void nextGuestIsSearchedCyclically() {
            assertThat(set(List.of(), THREE, VIKA, "0.60"))
                    .containsExactly(row(VIKA, "0.60"), row(ANYA, "0.40"));
        }

        @Test
        void zeroShareGivesWholeItemToNextGuestAndStays() {
            assertThat(set(List.of(), THREE, ANYA, "0"))
                    .containsExactly(row(ANYA, "0.00"), row(BORYA, "1.00"));
        }

        @Test
        void fullShareNeedsNobodyElse() {
            assertThat(set(List.of(), THREE, ANYA, "1.00")).containsExactly(row(ANYA, "1.00"));
        }

        @Test
        void singleGuestSessionCannotSplit() {
            assertThatThrownBy(() -> set(List.of(), List.of(ANYA), ANYA, "0.70"))
                    .isInstanceOf(ApiException.class)
                    .satisfies(e -> assertThat(((ApiException) e).getStatusCode().value()).isEqualTo(409));
        }
    }

    @Nested
    class ShareDecreased {
        @Test
        void newGuestDecreaseGivesRemainderToNextGuest() {
            // Боря: 0.30 → 0.20 → Вика 0.10
            var rows = List.of(row(ANYA, "0.70"), row(BORYA, "0.30"));
            assertThat(set(rows, THREE, BORYA, "0.20"))
                    .containsExactly(row(ANYA, "0.70"), row(BORYA, "0.20"), row(VIKA, "0.10"));
        }

        @Test
        void freedShareGoesToNewGuestAtTheEnd() {
            // Аня: 0.70 → 0.50 → Боря остаётся 0.30, Вика 0.20
            var rows = List.of(row(ANYA, "0.70"), row(BORYA, "0.30"));
            assertThat(set(rows, THREE, ANYA, "0.50"))
                    .containsExactly(row(ANYA, "0.50"), row(BORYA, "0.30"), row(VIKA, "0.20"));
        }

        @Test
        void soleGuestLoweredFromOne() {
            assertThat(set(List.of(row(BORYA, "1.00")), THREE, BORYA, "0.70"))
                    .containsExactly(row(BORYA, "0.70"), row(VIKA, "0.30"));
        }

        @Test
        void allGuestsOnItem_lastEdited_previousGetsRemainder() {
            // Аня 0.70, Боря 0.30; Боря → 0.10 → Аня 0.90
            var rows = List.of(row(ANYA, "0.70"), row(BORYA, "0.30"));
            assertThat(set(rows, List.of(ANYA, BORYA), BORYA, "0.10"))
                    .containsExactly(row(ANYA, "0.90"), row(BORYA, "0.10"));
        }

        @Test
        void allGuestsOnItem_notLastEdited_nextOnItemGetsRemainder() {
            var rows = List.of(row(ANYA, "0.50"), row(BORYA, "0.30"), row(VIKA, "0.20"));
            assertThat(set(rows, THREE, ANYA, "0.40"))
                    .containsExactly(row(ANYA, "0.40"), row(BORYA, "0.40"), row(VIKA, "0.20"));
        }

        @Test
        void zeroShareKeepsGuestOnItem() {
            var rows = List.of(row(ANYA, "0.70"), row(BORYA, "0.30"));
            assertThat(set(rows, THREE, BORYA, "0"))
                    .containsExactly(row(ANYA, "0.70"), row(BORYA, "0.00"), row(VIKA, "0.30"));
        }

        @Test
        void chainContinuesThroughTheList() {
            var rows = set(List.of(), FOUR, ANYA, "0.70");          // Аня 0.70, Боря 0.30
            rows = set(rows, FOUR, BORYA, "0.20");                   // + Вика 0.10
            rows = set(rows, FOUR, VIKA, "0.05");                    // + Гоша 0.05
            assertThat(rows).containsExactly(
                    row(ANYA, "0.70"), row(BORYA, "0.20"), row(VIKA, "0.05"), row(GOSHA, "0.05"));
        }
    }

    @Nested
    class ShareIncreased {
        @Test
        void takesFromLastThenPrevious() {
            // Аня 0.50, Боря 0.30, Вика 0.20; Аня → 0.90 → Боря 0.10, Вика 0.00
            var rows = List.of(row(ANYA, "0.50"), row(BORYA, "0.30"), row(VIKA, "0.20"));
            assertThat(set(rows, THREE, ANYA, "0.90"))
                    .containsExactly(row(ANYA, "0.90"), row(BORYA, "0.10"), row(VIKA, "0.00"));
        }

        @Test
        void lastGuestIncreasedTakesFromPreviousOnes() {
            var rows = List.of(row(ANYA, "0.50"), row(BORYA, "0.30"), row(VIKA, "0.20"));
            assertThat(set(rows, THREE, VIKA, "0.60"))
                    .containsExactly(row(ANYA, "0.40"), row(BORYA, "0.00"), row(VIKA, "0.60"));
        }

        @Test
        void fullShareZeroesOthersButKeepsThem() {
            var rows = List.of(row(ANYA, "0.70"), row(BORYA, "0.30"));
            assertThat(set(rows, THREE, ANYA, "1.00"))
                    .containsExactly(row(ANYA, "1.00"), row(BORYA, "0.00"));
        }
    }

    @Nested
    class GuestAddedToOccupiedItem {
        @Test
        void defaultZeroChangesNothingForOthers() {
            var rows = List.of(row(ANYA, "0.70"), row(BORYA, "0.30"));
            assertThat(set(rows, THREE, VIKA, "0"))
                    .containsExactly(row(ANYA, "0.70"), row(BORYA, "0.30"), row(VIKA, "0.00"));
        }

        @Test
        void othersShrinkProportionallyToExactlyOne() {
            var rows = List.of(row(ANYA, "0.50"), row(BORYA, "0.50"));
            assertThat(set(rows, THREE, VIKA, "0.33"))
                    .containsExactly(row(ANYA, "0.34"), row(BORYA, "0.33"), row(VIKA, "0.33"));
        }
    }

    @Test
    void sameShareChangesNothing() {
        var rows = List.of(row(ANYA, "0.70"), row(BORYA, "0.30"));
        assertThat(set(rows, THREE, ANYA, "0.70")).isEqualTo(rows);
    }

    @Test
    void sumIsAlwaysExactlyOneOnRandomEdits() {
        var random = new java.util.Random(7);
        for (int run = 0; run < 500; run++) {
            List<Long> guests = FOUR.subList(0, 2 + random.nextInt(3));
            List<Row> rows = List.of();
            for (int step = 0; step < 20; step++) {
                long guest = guests.get(random.nextInt(guests.size()));
                rows = set(rows, guests, guest, BigDecimal.valueOf(random.nextInt(101), 2).toPlainString());
                int sum = rows.stream().mapToInt(r -> ShareRebalancer.hundredths(r.share())).sum();
                assertThat(sum).as("run %d step %d: %s", run, step, rows).isEqualTo(100);
                assertThat(rows).allSatisfy(r -> assertThat(r.share()).isBetween(BigDecimal.ZERO, BigDecimal.ONE));
                assertThat(rows.stream().map(Row::guestId).distinct().count()).isEqualTo(rows.size());
            }
        }
    }
}
