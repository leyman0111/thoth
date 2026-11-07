package ru.leyman.thoth.telegram;

import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

/**
 * Минимальный клиент Telegram Bot API (https://core.telegram.org/bots/api) без сторонних библиотек.
 * Токен входит в URL, поэтому все сообщения об ошибках очищаются от него перед логированием.
 */
public class TelegramBotApi {

    private final RestClient restClient;
    private final JsonMapper jsonMapper;
    private final String token;

    public TelegramBotApi(String baseUrl, String token, Duration readTimeout, JsonMapper jsonMapper) {
        this.token = token;
        this.jsonMapper = jsonMapper;
        var requestFactory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build());
        requestFactory.setReadTimeout(readTimeout);
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl + "/bot" + token)
                .requestFactory(requestFactory)
                .build();
    }

    /**
     * Вызывает метод Bot API и возвращает поле result.
     *
     * @throws TelegramApiException ok=false или HTTP-ошибка; errorCode — код ошибки Telegram (409, 429...)
     */
    public JsonNode call(String method, Map<String, ?> params) {
        String body;
        try {
            body = restClient.post()
                    .uri("/" + method)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(jsonMapper.writeValueAsString(params))
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            throw new TelegramApiException(method, e.getStatusCode().value(),
                    describe(e.getResponseBodyAsString()));
        } catch (RestClientException e) {
            throw new TelegramApiException(method, 0, sanitize(e.getMessage()));
        }

        try {
            JsonNode response = jsonMapper.readTree(body);
            if (!response.path("ok").asBoolean(false)) {
                throw new TelegramApiException(method, response.path("error_code").asInt(0),
                        response.path("description").asString(""));
            }
            return response.path("result");
        } catch (JacksonException e) {
            throw new TelegramApiException(method, 0, "Неожиданный ответ Bot API");
        }
    }

    private String describe(String body) {
        try {
            return jsonMapper.readTree(body).path("description").asString(sanitize(body));
        } catch (JacksonException e) {
            return sanitize(body);
        }
    }

    private String sanitize(String message) {
        return message == null ? null : message.replace(token, "***");
    }

    public static class TelegramApiException extends RuntimeException {
        private final int errorCode;

        public TelegramApiException(String method, int errorCode, String description) {
            super(method + ": " + (errorCode > 0 ? errorCode + " " : "") + description);
            this.errorCode = errorCode;
        }

        public int errorCode() {
            return errorCode;
        }
    }
}
