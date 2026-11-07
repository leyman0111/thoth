package ru.leyman.thoth.openrouter;

import io.netty.channel.ChannelOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.netty.http.client.HttpClient;
import ru.leyman.thoth.config.ThothProperties;
import ru.leyman.thoth.service.ApiException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Распознавание чека через OpenRouter Chat Completions API на WebClient.
 * <p>
 * OpenRouter принимает изображение только внутри JSON как data-URL (base64) — multipart-загрузки файлов
 * у Chat Completions нет. Поэтому тело запроса пишется потоково: JSON до картинки → base64 прямо из
 * InputStream загруженного файла → JSON после. Файл не сохраняется и не держится в памяти целиком.
 * <p>
 * Приложение работает на Spring MVC, поэтому результат ожидается блокирующе ({@code block()}).
 */
@Component
public class OpenRouterClient implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(OpenRouterClient.class);

    static final String PROMPT = """
            Ты — эксперт по OCR. Извлеки из этого фото ресторанного чека ТОЛЬКО JSON-массив позиций. \
            Для каждой позиции должны быть поля: name (строка, название блюда/напитка), price (число, цена), \
            currency (строка, например RUB). Игнорируй подытоги, налоги и итоговую сумму. \
            Если валюта не указана, по умолчанию RUB. Верни валидный JSON.""";

    private static final String IMAGE_PLACEHOLDER = "\"__THOTH_IMAGE__\"";
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;

    private final WebClient webClient;
    private final JsonMapper jsonMapper;
    private final ReceiptResponseParser parser;
    private final ThothProperties.OpenRouter props;
    /** Поток файла читается блокирующе, поэтому запись тела идёт в отдельном (виртуальном) потоке. */
    private final ExecutorService bodyWriter = Executors.newVirtualThreadPerTaskExecutor();

    public OpenRouterClient(ThothProperties properties, JsonMapper jsonMapper, ReceiptResponseParser parser) {
        this.props = properties.openrouter();
        this.jsonMapper = jsonMapper;
        this.parser = parser;

        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) props.connectTimeout().toMillis())
                .responseTimeout(props.readTimeout())
                .proxyWithSystemProperties();

        WebClient.Builder builder = WebClient.builder()
                .baseUrl(props.baseUrl())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(MAX_RESPONSE_BYTES))
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.apiKey());
        if (StringUtils.hasText(props.appUrl())) {
            builder.defaultHeader("HTTP-Referer", props.appUrl());
        }
        if (StringUtils.hasText(props.appTitle())) {
            builder.defaultHeader("X-Title", props.appTitle());
        }
        this.webClient = builder.build();
    }

    /**
     * @param image     поток изображения; читается один раз, закрывает вызывающий
     * @param mediaType image/jpeg, image/png или image/webp
     * @throws ApiException 502 — OpenRouter недоступен или ответил ошибкой; 422 — ответ модели не разобрать
     */
    public RecognizedReceipt recognize(InputStream image, MediaType mediaType) {
        if (!StringUtils.hasText(props.apiKey())) {
            throw ApiException.badGateway("Не задан ключ OpenRouter (thoth.openrouter.api-key)", null);
        }

        String[] envelope = buildEnvelope();
        byte[] prefix = (envelope[0] + "\"data:" + mediaType + ";base64,").getBytes(StandardCharsets.UTF_8);
        byte[] suffix = ("\"" + envelope[1]).getBytes(StandardCharsets.UTF_8);

        long started = System.nanoTime();
        String responseBody;
        try {
            responseBody = webClient.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(BodyInserters.fromOutputStream(out -> writeBody(out, prefix, image, suffix), bodyWriter))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
        } catch (WebClientResponseException e) {
            log.warn("OpenRouter ответил {}: {}", e.getStatusCode(), abbreviate(e.getResponseBodyAsString()));
            throw ApiException.badGateway(describeUpstreamError(e.getStatusCode().value()), e);
        } catch (WebClientRequestException e) {
            log.warn("OpenRouter недоступен: {}", e.getMessage());
            throw ApiException.badGateway("Сервис распознавания недоступен или не ответил вовремя", e);
        }
        long tookMs = (System.nanoTime() - started) / 1_000_000;
        String content = extractContent(responseBody, tookMs);
        try {
            return parser.parse(content);
        } catch (ApiException e) {
            log.warn("Ответ модели не разобран: {}", abbreviate(content));
            throw e;
        }
    }

    private static void writeBody(OutputStream out, byte[] prefix, InputStream image, byte[] suffix) {
        try {
            out.write(prefix);
            try (OutputStream base64 = Base64.getEncoder().wrap(new NonClosingOutputStream(out))) {
                image.transferTo(base64);
            }
            out.write(suffix);
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось передать файл в OpenRouter", e);
        }
    }

    /** JSON запроса, разрезанный по месту картинки: [до, после]. */
    private String[] buildEnvelope() {
        Map<String, Object> request = new LinkedHashMap<>();
        List<String> fallbacks = props.fallbackModels().stream().filter(StringUtils::hasText).map(String::strip).toList();
        if (fallbacks.isEmpty()) {
            request.put("model", props.model());
        } else {
            // Model fallbacks OpenRouter: модели пробуются по порядку, если предыдущая недоступна или в лимите
            List<String> models = new ArrayList<>();
            models.add(props.model());
            models.addAll(fallbacks);
            request.put("models", models);
        }
        request.put("temperature", 0);
        request.put("messages", List.of(
                Map.of("role", "user", "content", List.of(
                        Map.of("type", "text", "text", PROMPT),
                        Map.of("type", "image_url", "image_url", Map.of("url", "__THOTH_IMAGE__"))))));

        String json = jsonMapper.writeValueAsString(request);
        int at = json.indexOf(IMAGE_PLACEHOLDER);
        return new String[]{json.substring(0, at), json.substring(at + IMAGE_PLACEHOLDER.length())};
    }

    /** Понятное сообщение для частых ошибок OpenRouter. */
    static String describeUpstreamError(int status) {
        return switch (status) {
            case 401 -> "Сервис распознавания: неверный ключ OpenRouter";
            case 402 -> "Сервис распознавания: недостаточно кредитов OpenRouter (у бесплатных моделей — "
                    + "при отрицательном балансе)";
            case 429 -> "Сервис распознавания: превышен лимит запросов модели. Попробуйте через минуту";
            default -> "Сервис распознавания вернул ошибку " + status;
        };
    }

    /** choices[0].message.content; конверт OpenRouter неожиданного вида — 502, пустой ответ модели — 422 (в парсере). */
    private String extractContent(String responseBody, long tookMs) {
        ChatCompletion completion;
        try {
            completion = responseBody == null ? null : jsonMapper.readValue(responseBody, ChatCompletion.class);
        } catch (JacksonException e) {
            log.warn("Неожиданный ответ OpenRouter: {}", abbreviate(responseBody));
            throw ApiException.badGateway("Сервис распознавания вернул ответ в неверном формате", e);
        }
        if (completion != null) {
            // При fallback ответила, возможно, не основная модель — в логе фактическая
            log.info("Ответ OpenRouter: модель {} за {} мс", completion.model(), tookMs);
        }
        if (completion == null || completion.choices() == null || completion.choices().isEmpty()) {
            log.warn("OpenRouter вернул ответ без choices: {}", abbreviate(responseBody));
            throw ApiException.badGateway("Сервис распознавания вернул пустой ответ", null);
        }
        ChatCompletion.Message message = completion.choices().getFirst().message();
        return message == null ? null : message.content();
    }

    private static String abbreviate(String s) {
        return s == null || s.length() <= 500 ? s : s.substring(0, 500) + "…";
    }

    @Override
    public void destroy() {
        bodyWriter.shutdown();
    }

    record ChatCompletion(String model, List<Choice> choices) {
        record Choice(Message message) {
        }

        record Message(String content) {
        }
    }

    /** Base64-обёртка при close() дописывает паддинг; поток запроса при этом закрывать нельзя. */
    private static final class NonClosingOutputStream extends FilterOutputStream {
        NonClosingOutputStream(OutputStream out) {
            super(out);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
        }

        @Override
        public void close() throws IOException {
            flush();
        }
    }
}
