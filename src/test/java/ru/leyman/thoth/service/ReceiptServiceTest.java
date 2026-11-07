package ru.leyman.thoth.service;

import org.junit.jupiter.api.Test;
import ru.leyman.thoth.config.ThothProperties;
import ru.leyman.thoth.openrouter.RecognizedReceipt;
import ru.leyman.thoth.openrouter.RecognizedReceipt.Line;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReceiptServiceTest {

    static ThothProperties properties(Map<String, BigDecimal> rates) {
        return new ThothProperties(
                new ThothProperties.OpenRouter("https://openrouter.ai/api/v1", "k", "m", List.of(),
                        Duration.ofSeconds(1), Duration.ofSeconds(1), null, "Thoth"),
                new ThothProperties.Receipt("RUB", rates, 200),
                new ThothProperties.Cors(List.of()),
                null);
    }

    private final ThothProperties props = properties(Map.of("usd", new BigDecimal("90.50")));
    private final ReceiptService service = new ReceiptService(null, null, new CurrencyConverter(props), props);

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    @Test
    void keepsRublesAndConvertsForeignCurrencyByFixedRate() {
        var items = service.normalize(new RecognizedReceipt(List.of(
                new Line("Пицца  Маргарита", d("890"), "RUB"),
                new Line("Чай", d("150.5"), null),
                new Line("Burger", d("12.99"), "usd"),
                new Line("Кофе", d("200"), "₽"))));

        assertThat(items).containsExactly(
                new SessionService.ReceiptItem("Пицца Маргарита", d("890.00"), "RUB"),
                new SessionService.ReceiptItem("Чай", d("150.50"), "RUB"),
                new SessionService.ReceiptItem("Burger", d("1175.60"), "RUB"),   // 12.99 × 90.50 = 1175.595
                new SessionService.ReceiptItem("Кофе", d("200.00"), "RUB"));
    }

    @Test
    void currencyWithoutRateIsUnprocessable() {
        assertThatThrownBy(() -> service.normalize(new RecognizedReceipt(List.of(new Line("Beer", d("5"), "EUR")))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("EUR")
                .satisfies(e -> assertThat(((ApiException) e).getStatusCode().value()).isEqualTo(422));
    }

    @Test
    void skipsInvalidLinesButFailsWhenNothingLeft() {
        var items = service.normalize(new RecognizedReceipt(List.of(
                new Line(" ", d("100"), "RUB"),
                new Line("Скидка", d("-50"), "RUB"),
                new Line("Хлеб", d("40"), "RUB"))));
        assertThat(items).extracting(SessionService.ReceiptItem::name).containsExactly("Хлеб");

        assertThatThrownBy(() -> service.normalize(new RecognizedReceipt(List.of(new Line("Скидка", d("-50"), "RUB")))))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatusCode().value()).isEqualTo(422));
    }
}
