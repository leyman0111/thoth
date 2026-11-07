package ru.leyman.thoth.openrouter;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import ru.leyman.thoth.service.ApiException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Разбор текстового ответа LLM в позиции чека.
 * <p>
 * Ожидается JSON-массив {@code [{"name": ..., "price": ..., "currency": ...}]}, но модели нередко
 * отступают от формата, поэтому парсер терпим к типичным отклонениям:
 * <ul>
 *     <li>JSON в обёртке {@code ```json ... ```} или с текстом вокруг;</li>
 *     <li>массив внутри объекта: {@code {"items": [...]}};</li>
 *     <li>цена строкой: {@code "1 290,50"}.</li>
 * </ul>
 * Если JSON нельзя разобрать или в нём нет ни одной позиции — {@link ApiException} 422.
 */
@Component
public class ReceiptResponseParser {

    private final JsonMapper jsonMapper;

    public ReceiptResponseParser(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public RecognizedReceipt parse(String content) {
        if (!StringUtils.hasText(content)) {
            throw ApiException.unprocessable("Модель вернула пустой ответ. Попробуйте сфотографировать чек ещё раз");
        }
        JsonNode root = readJson(stripCodeFence(content));
        JsonNode array = findItemsArray(root);
        if (array == null || array.isEmpty()) {
            throw ApiException.unprocessable("Не удалось найти позиции на фото. Сфотографируйте чек целиком и при хорошем освещении");
        }

        List<RecognizedReceipt.Line> lines = new ArrayList<>(array.size());
        for (JsonNode node : array.values()) {
            if (!node.isObject()) {
                continue;
            }
            String name = text(node.get("name"));
            BigDecimal price = decimal(node.get("price"));
            if (name == null || price == null) {
                continue;
            }
            lines.add(new RecognizedReceipt.Line(name, price, text(node.get("currency"))));
        }
        if (lines.isEmpty()) {
            throw ApiException.unprocessable("В ответе модели нет позиций с названием и ценой");
        }
        return new RecognizedReceipt(lines);
    }

    private JsonNode readJson(String content) {
        try {
            return jsonMapper.readTree(content);
        } catch (JacksonException first) {
            // Текст вокруг JSON: берём от первой [ до последней ]
            int start = content.indexOf('[');
            int end = content.lastIndexOf(']');
            if (start >= 0 && end > start) {
                try {
                    return jsonMapper.readTree(content.substring(start, end + 1));
                } catch (JacksonException ignored) {
                    // ниже — общая ошибка
                }
            }
            throw ApiException.unprocessable("Модель вернула невалидный JSON");
        }
    }

    /** Массив в корне, массив в поле объекта или один объект-позиция. */
    private JsonNode findItemsArray(JsonNode root) {
        if (root == null) {
            return null;
        }
        if (root.isArray()) {
            return root;
        }
        if (root.isObject()) {
            for (String key : List.of("items", "positions", "позиции")) {
                JsonNode candidate = root.get(key);
                if (candidate != null && candidate.isArray()) {
                    return candidate;
                }
            }
            for (Map.Entry<String, JsonNode> property : root.properties()) {
                if (property.getValue().isArray()) {
                    return property.getValue();
                }
            }
            if (root.has("name") && root.has("price")) {
                return jsonMapper.createArrayNode().add(root);
            }
        }
        return null;
    }

    private static String text(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.isString() ? node.stringValue() : node.asString();
        return StringUtils.hasText(value) ? value.strip() : null;
    }

    private static BigDecimal decimal(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        if (node.isString()) {
            // "1 290,50" / "1290.50 ₽" → 1290.50
            String cleaned = node.stringValue().replaceAll("[\\s\\u00A0]", "").replace(',', '.')
                    .replaceAll("[^0-9.\\-]", "");
            while (cleaned.indexOf('.') != cleaned.lastIndexOf('.')) {
                cleaned = cleaned.replaceFirst("\\.", ""); // "1.290.50": точки-разделители тысяч
            }
            try {
                return cleaned.isEmpty() ? null : new BigDecimal(cleaned);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    static String stripCodeFence(String content) {
        String s = content.strip();
        if (s.startsWith("```")) {
            int firstNewLine = s.indexOf('\n');
            int lastFence = s.lastIndexOf("```");
            if (firstNewLine > 0 && lastFence > firstNewLine) {
                return s.substring(firstNewLine + 1, lastFence).strip();
            }
        }
        return s;
    }
}
