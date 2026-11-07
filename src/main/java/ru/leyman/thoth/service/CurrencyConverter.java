package ru.leyman.thoth.service;

import org.springframework.stereotype.Component;
import ru.leyman.thoth.config.ThothProperties;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Map;

/**
 * Приводит цену к базовой валюте (RUB) по фиксированным курсам из
 * {@code thoth.receipt.exchange-rates}. Нет курса — {@link ApiException} 422.
 */
@Component
public class CurrencyConverter {

    /** Что модели пишут вместо ISO-кода, несмотря на инструкцию. */
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("₽", "RUB"), Map.entry("Р", "RUB"), Map.entry("Р.", "RUB"), Map.entry("РУБ", "RUB"),
            Map.entry("РУБ.", "RUB"), Map.entry("РУБЛЬ", "RUB"), Map.entry("РУБЛЕЙ", "RUB"), Map.entry("RUR", "RUB"),
            Map.entry("$", "USD"), Map.entry("US$", "USD"), Map.entry("€", "EUR"), Map.entry("₸", "KZT"),
            Map.entry("ТГ", "KZT"), Map.entry("£", "GBP"), Map.entry("₺", "TRY"), Map.entry("₾", "GEL"),
            Map.entry("֏", "AMD"));

    private final String base;
    private final Map<String, BigDecimal> rates;

    public CurrencyConverter(ThothProperties properties) {
        this.base = properties.receipt().baseCurrency();
        this.rates = properties.receipt().exchangeRates();
    }

    public String baseCurrency() {
        return base;
    }

    /** null/пусто → базовая валюта; символы и сокращения → ISO-код; иначе 422. */
    public String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return base;
        }
        String code = raw.strip().toUpperCase(Locale.ROOT);
        code = ALIASES.getOrDefault(code, code);
        if (!code.matches("[A-Z]{3}")) {
            throw ApiException.unprocessable("Не удалось определить валюту «" + raw.strip() + "»");
        }
        return code;
    }

    /** Цена в базовой валюте, округлённая до копеек. */
    public BigDecimal toBase(BigDecimal amount, String currency) {
        if (base.equals(currency)) {
            return amount.setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal rate = rates.get(currency);
        if (rate == null || rate.signum() <= 0) {
            throw ApiException.unprocessable("Чек в валюте " + currency + " не поддерживается: курс к " + base + " не задан");
        }
        return amount.multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }
}
