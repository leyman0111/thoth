package ru.leyman.thoth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Настройки приложения (префикс {@code thoth} в application.yml).
 */
@ConfigurationProperties("thoth")
public record ThothProperties(
        @DefaultValue OpenRouter openrouter,
        @DefaultValue Receipt receipt,
        @DefaultValue Cors cors,
        @DefaultValue Telegram telegram
) {

    /**
     * @param model          основная модель
     * @param fallbackModels резервные модели: OpenRouter переключается на них, если основная недоступна,
     *                       упёрлась в лимит или вернула ошибку. Платная модель в этом списке тарифицируется,
     *                       даже если основная бесплатная
     */
    public record OpenRouter(
            @DefaultValue("https://openrouter.ai/api/v1") String baseUrl,
            String apiKey,
            @DefaultValue("nvidia/nemotron-3.5-super-vl:free") String model,
            @DefaultValue List<String> fallbackModels,
            @DefaultValue("10s") Duration connectTimeout,
            @DefaultValue("90s") Duration readTimeout,
            /* Необязательные заголовки атрибуции OpenRouter (HTTP-Referer, X-Title) */
            String appUrl,
            @DefaultValue("Thoth") String appTitle
    ) {
        public OpenRouter {
            fallbackModels = fallbackModels == null ? List.of() : List.copyOf(fallbackModels);
        }
    }

    /**
     * @param baseCurrency  валюта, в которой хранятся все позиции
     * @param exchangeRates фиксированные курсы: сколько baseCurrency за 1 единицу валюты (USD: 95.50).
     *                      Валюта без курса — ошибка 422 при загрузке чека
     */
    public record Receipt(
            @DefaultValue("RUB") String baseCurrency,
            Map<String, BigDecimal> exchangeRates,
            @DefaultValue("200") int maxItems
    ) {
        public Receipt {
            baseCurrency = baseCurrency.toUpperCase(Locale.ROOT);
            exchangeRates = exchangeRates == null ? Map.of() : exchangeRates.entrySet().stream()
                    .collect(Collectors.toUnmodifiableMap(e -> e.getKey().strip().toUpperCase(Locale.ROOT), Map.Entry::getValue));
        }
    }

    public record Cors(@DefaultValue List<String> allowedOrigins) {
    }

    /**
     * @param botToken       токен от @BotFather; нужен и для бота, и для проверки подписи initData
     * @param webAppUrl      HTTPS-адрес Mini App (корень этого приложения), его открывают кнопки бота
     * @param initDataMaxAge сколько действительна подпись initData после открытия Mini App
     * @param devMode        принимать X-Telegram-User-Id без подписи — только для отладки в браузере
     * @param botEnabled     запускать long polling бота (выключите, если обновления читает другой сервис)
     */
    public record Telegram(
            String botToken,
            String webAppUrl,
            @DefaultValue("24h") Duration initDataMaxAge,
            @DefaultValue("false") boolean devMode,
            @DefaultValue("true") boolean botEnabled,
            @DefaultValue("https://api.telegram.org") String apiBaseUrl
    ) {
    }
}
