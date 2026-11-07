package ru.leyman.thoth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import ru.leyman.thoth.api.dto.SessionResponse;
import ru.leyman.thoth.config.ThothProperties;
import ru.leyman.thoth.domain.SessionStatus;
import ru.leyman.thoth.openrouter.OpenRouterClient;
import ru.leyman.thoth.openrouter.RecognizedReceipt;

import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Загрузка и распознавание чека.
 * <p>
 * Вызов OpenRouter длится секунды, поэтому он выполняется ВНЕ транзакции БД:
 * <ol>
 *     <li>транзакция 1 — проверка и атомарный перевод сессии в PROCESSING;</li>
 *     <li>HTTP-запрос к OpenRouter (соединение с БД не удерживается);</li>
 *     <li>транзакция 2 — замена позиций и READY, либо откат статуса при любой ошибке.</li>
 * </ol>
 */
@Service
public class ReceiptService {

    private static final Logger log = LoggerFactory.getLogger(ReceiptService.class);

    static final Set<MediaType> SUPPORTED_TYPES = Set.of(
            MediaType.IMAGE_JPEG, MediaType.IMAGE_PNG, MediaType.parseMediaType("image/webp"));

    private static final int MAX_NAME = 255;
    private static final BigDecimal MAX_PRICE = new BigDecimal("9999999999.99"); // NUMERIC(12,2)

    private final SessionService sessionService;
    private final OpenRouterClient openRouter;
    private final CurrencyConverter currencyConverter;
    private final int maxItems;

    public ReceiptService(SessionService sessionService, OpenRouterClient openRouter,
                          CurrencyConverter currencyConverter, ThothProperties properties) {
        this.sessionService = sessionService;
        this.openRouter = openRouter;
        this.currencyConverter = currencyConverter;
        this.maxItems = properties.receipt().maxItems();
    }

    public SessionResponse recognize(UUID sessionId, long userId, InputStream image, String contentType) {
        MediaType mediaType = resolveMediaType(contentType);

        SessionStatus previous = sessionService.beginProcessing(sessionId, userId);
        try {
            RecognizedReceipt receipt = openRouter.recognize(image, mediaType);
            List<SessionService.ReceiptItem> items = normalize(receipt);
            sessionService.completeProcessing(sessionId, items);
            log.info("Сессия {}: сохранено позиций {}", sessionId, items.size());
        } catch (RuntimeException e) {
            try {
                sessionService.abortProcessing(sessionId, previous);
            } catch (RuntimeException rollbackError) {
                e.addSuppressed(rollbackError);
            }
            throw e;
        }
        return sessionService.get(sessionId, userId);
    }

    private static MediaType resolveMediaType(String contentType) {
        if (contentType == null) {
            throw ApiException.unsupportedMediaType("Не указан тип файла. Поддерживаются JPEG, PNG и WebP");
        }
        MediaType type;
        try {
            type = MediaType.parseMediaType(contentType);
        } catch (IllegalArgumentException e) {
            throw ApiException.unsupportedMediaType("Некорректный тип файла: " + contentType);
        }
        MediaType bare = new MediaType(type.getType(), type.getSubtype());
        if (!SUPPORTED_TYPES.contains(bare)) {
            throw ApiException.unsupportedMediaType("Тип " + bare + " не поддерживается. Загрузите JPEG, PNG или WebP");
        }
        return bare;
    }

    /**
     * Проверка позиций от модели и перевод цен в базовую валюту (RUB).
     * Строки без названия, с отрицательной или нереальной ценой пропускаются;
     * если не осталось ни одной — 422. Валюта без курса — 422 для всего чека.
     */
    List<SessionService.ReceiptItem> normalize(RecognizedReceipt receipt) {
        if (receipt == null || receipt.items() == null || receipt.items().isEmpty()) {
            throw ApiException.unprocessable("Не удалось найти позиции на фото");
        }
        String base = currencyConverter.baseCurrency();

        List<SessionService.ReceiptItem> result = new ArrayList<>();
        for (RecognizedReceipt.Line line : receipt.items()) {
            if (line == null || line.name() == null || line.name().isBlank() || line.price() == null) {
                continue;
            }
            String currency = currencyConverter.normalize(line.currency());
            BigDecimal price = currencyConverter.toBase(line.price(), currency).setScale(2, RoundingMode.HALF_UP);
            if (price.signum() < 0 || price.compareTo(MAX_PRICE) > 0) {
                continue;
            }
            String name = line.name().strip().replaceAll("\\s+", " ");
            if (name.length() > MAX_NAME) {
                name = name.substring(0, MAX_NAME);
            }
            result.add(new SessionService.ReceiptItem(name, price, base));
            if (result.size() > maxItems) {
                throw ApiException.unprocessable("В чеке слишком много позиций (больше " + maxItems + ")");
            }
        }
        if (result.isEmpty()) {
            throw ApiException.unprocessable("Не удалось распознать ни одной позиции с ценой");
        }
        return result;
    }
}
