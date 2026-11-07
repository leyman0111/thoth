package ru.leyman.thoth.telegram;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import ru.leyman.thoth.config.ThothProperties;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Проверка Telegram.WebApp.initData по документации
 * «Validating data received via the Mini App» (https://core.telegram.org/bots/webapps):
 * <pre>
 * data_check_string = все поля, кроме hash, отсортированные по ключу, "key=value" через \n
 * secret_key        = HMAC_SHA256(key = "WebAppData", data = bot_token)
 * hash              = hex(HMAC_SHA256(key = secret_key, data = data_check_string))
 * </pre>
 * Поле signature (Ed25519 для сторонних сервисов) остаётся в data_check_string — исключается только hash.
 */
@Component
public class InitDataValidator {

    /** Допуск на расхождение часов клиента Telegram и сервера. */
    private static final Duration CLOCK_SKEW = Duration.ofMinutes(1);

    private final byte[] secretKey;
    private final Duration maxAge;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    @Autowired
    public InitDataValidator(ThothProperties properties, JsonMapper jsonMapper) {
        this(properties.telegram().botToken(), properties.telegram().initDataMaxAge(), jsonMapper, Clock.systemUTC());
    }

    InitDataValidator(String botToken, Duration maxAge, JsonMapper jsonMapper, Clock clock) {
        this.secretKey = StringUtils.hasText(botToken)
                ? hmac("WebAppData".getBytes(StandardCharsets.UTF_8), botToken.strip().getBytes(StandardCharsets.UTF_8))
                : null;
        this.maxAge = maxAge;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    public boolean isConfigured() {
        return secretKey != null;
    }

    /**
     * @param initData строка Telegram.WebApp.initData как есть (query string)
     * @throws InvalidInitDataException подпись не сходится, данные устарели или в них нет пользователя
     */
    public TelegramUser validate(String initData) {
        if (secretKey == null) {
            throw new InvalidInitDataException("Токен бота не задан на сервере");
        }
        if (!StringUtils.hasText(initData)) {
            throw new InvalidInitDataException("Пустые данные авторизации");
        }

        Map<String, String> fields = parse(initData);
        String hash = fields.remove("hash");
        if (hash == null) {
            throw new InvalidInitDataException("В данных нет подписи");
        }

        String dataCheckString = fields.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("\n"));
        byte[] expected = HexFormat.of().formatHex(hmac(secretKey, dataCheckString.getBytes(StandardCharsets.UTF_8)))
                .getBytes(StandardCharsets.US_ASCII);
        // Сравнение за постоянное время — не даёт подбирать подпись по времени ответа
        if (!MessageDigest.isEqual(expected, hash.toLowerCase().getBytes(StandardCharsets.US_ASCII))) {
            throw new InvalidInitDataException("Подпись данных не совпадает");
        }

        checkAuthDate(fields.get("auth_date"));
        return parseUser(fields.get("user"));
    }

    private void checkAuthDate(String raw) {
        long seconds;
        try {
            seconds = Long.parseLong(raw);
        } catch (NumberFormatException | NullPointerException e) {
            throw new InvalidInitDataException("Некорректное auth_date");
        }
        Instant authDate = Instant.ofEpochSecond(seconds);
        Instant now = clock.instant();
        if (authDate.isAfter(now.plus(CLOCK_SKEW))) {
            throw new InvalidInitDataException("auth_date из будущего");
        }
        if (authDate.plus(maxAge).isBefore(now)) {
            throw new InvalidInitDataException("Данные авторизации устарели — откройте приложение заново");
        }
    }

    private TelegramUser parseUser(String json) {
        if (json == null) {
            throw new InvalidInitDataException("В данных нет пользователя");
        }
        try {
            JsonNode user = jsonMapper.readTree(json);
            JsonNode id = user.get("id");
            if (id == null || !id.canConvertToLong()) {
                throw new InvalidInitDataException("Некорректный id пользователя");
            }
            return new TelegramUser(id.asLong(), text(user, "username"), text(user, "first_name"), false);
        } catch (JacksonException e) {
            throw new InvalidInitDataException("Некорректный JSON пользователя");
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    /** Query string → отсортированные поля с URL-декодированными значениями. Повтор ключа — ошибка. */
    private static Map<String, String> parse(String initData) {
        Map<String, String> fields = new TreeMap<>();
        for (String pair : initData.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            if (fields.put(key, value) != null) {
                throw new InvalidInitDataException("Повторяющееся поле " + key);
            }
        }
        return fields;
    }

    static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 недоступен", e);
        }
    }

    public static class InvalidInitDataException extends RuntimeException {
        public InvalidInitDataException(String message) {
            super(message);
        }
    }
}
