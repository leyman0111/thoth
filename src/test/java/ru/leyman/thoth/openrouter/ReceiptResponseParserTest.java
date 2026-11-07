package ru.leyman.thoth.openrouter;

import org.junit.jupiter.api.Test;
import ru.leyman.thoth.openrouter.RecognizedReceipt.Line;
import ru.leyman.thoth.service.ApiException;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReceiptResponseParserTest {

    private final ReceiptResponseParser parser = new ReceiptResponseParser(JsonMapper.builder().build());

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    @Test
    void parsesPlainArray() {
        var receipt = parser.parse("""
                [{"name": "Борщ", "price": 450, "currency": "RUB"}, {"name": "Компот", "price": 120.5}]""");

        assertThat(receipt.items()).containsExactly(
                new Line("Борщ", d("450"), "RUB"),
                new Line("Компот", d("120.5"), null));
    }

    @Test
    void parsesFencedJsonWithTextAroundAndStringPrices() {
        var receipt = parser.parse("""
                Вот позиции:
                ```json
                [{"name": "Стейк", "price": "1 290,50", "currency": "RUB"}, {"name": "Вино", "price": "1.200.00"}]
                ```""");

        assertThat(receipt.items()).extracting(Line::price).containsExactly(d("1290.50"), d("1200.00"));
    }

    @Test
    void parsesArrayWrappedInObject() {
        var receipt = parser.parse("{\"items\": [{\"name\": \"Суп\", \"price\": 300}]}");
        assertThat(receipt.items()).containsExactly(new Line("Суп", d("300"), null));
    }

    @Test
    void textWithoutJsonIsUnprocessable() {
        assertUnprocessable("Извините, на фото не видно чека.");
    }

    @Test
    void brokenJsonIsUnprocessable() {
        assertUnprocessable("[{\"name\": \"Суп\", \"price\": ");
    }

    @Test
    void emptyArrayOrEmptyContentIsUnprocessable() {
        assertUnprocessable("[]");
        assertUnprocessable("   ");
        assertUnprocessable(null);
    }

    @Test
    void itemsWithoutNameOrPriceAreUnprocessable() {
        assertUnprocessable("[{\"name\": \"Суп\"}, {\"price\": 100}, {\"name\": \"Хлеб\", \"price\": \"бесплатно\"}]");
    }

    private void assertUnprocessable(String content) {
        assertThatThrownBy(() -> parser.parse(content))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatusCode().value()).isEqualTo(422));
    }
}
