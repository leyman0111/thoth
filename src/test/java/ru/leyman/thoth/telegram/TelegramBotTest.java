package ru.leyman.thoth.telegram;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.leyman.thoth.config.ThothProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Бот против локального сервера, имитирующего Bot API. */
class TelegramBotTest {

    private static final String TOKEN = "42:SECRET";
    private static final String WEB_APP = "https://thoth.example/";

    private final JsonMapper json = JsonMapper.builder().build();
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final Map<String, JsonNode> bodies = new ConcurrentHashMap<>();
    private final Map<String, String> replies = new ConcurrentHashMap<>();
    private HttpServer server;
    private TelegramBot bot;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/bot" + TOKEN + "/", exchange -> {
            String method = exchange.getRequestURI().getPath().substring(("/bot" + TOKEN + "/").length());
            calls.add(method);
            bodies.put(method, json.readTree(exchange.getRequestBody().readAllBytes()));
            byte[] response = replies.getOrDefault(method, "{\"ok\":true,\"result\":true}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(response.length == 0 ? 500 : 200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        var props = new ThothProperties(null, null, null,
                new ThothProperties.Telegram(TOKEN, WEB_APP, Duration.ofHours(24), false, true, base));
        bot = new TelegramBot(props, json);
        bot.useApi(new TelegramBotApi(base, TOKEN, Duration.ofSeconds(5), json));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void configuresMenuButtonAndCommand() {
        assertThat(bot.configure()).isTrue();

        assertThat(calls).containsExactly("deleteWebhook", "setMyCommands", "setChatMenuButton");
        JsonNode menu = bodies.get("setChatMenuButton").path("menu_button");
        assertThat(menu.path("type").asString()).isEqualTo("web_app");
        assertThat(menu.path("web_app").path("url").asString()).isEqualTo(WEB_APP);
        assertThat(bodies.get("setMyCommands").path("commands").get(0).path("command").asString()).isEqualTo("start");
    }

    @Test
    void repliesToStartInPrivateChatWithWebAppButton() {
        replies.put("getUpdates", """
                {"ok":true,"result":[
                  {"update_id":100,"message":{"message_id":1,"text":"/start","chat":{"id":555,"type":"private"}}},
                  {"update_id":101,"message":{"message_id":2,"text":"/start","chat":{"id":-777,"type":"group"}}}
                ]}""");

        bot.pollOnce();

        assertThat(calls).containsExactly("getUpdates", "sendMessage");
        JsonNode send = bodies.get("sendMessage");
        assertThat(send.path("chat_id").asLong()).isEqualTo(555);
        JsonNode button = send.path("reply_markup").path("inline_keyboard").get(0).get(0);
        assertThat(button.path("text").asString()).isEqualTo(TelegramBot.BUTTON_TEXT);
        assertThat(button.path("web_app").path("url").asString()).isEqualTo(WEB_APP);

        // следующий опрос продолжает с update_id + 1, чтобы не получить те же сообщения снова
        replies.put("getUpdates", "{\"ok\":true,\"result\":[]}");
        bot.pollOnce();
        assertThat(bodies.get("getUpdates").path("offset").asLong()).isEqualTo(102);
    }

    @Test
    void telegramErrorDoesNotLeakToken() {
        replies.put("setMyCommands", "{\"ok\":false,\"error_code\":401,\"description\":\"Unauthorized\"}");
        assertThat(bot.configure()).isFalse();

        // Сетевая ошибка: в сообщении Spring есть URL с токеном — он должен быть вырезан
        var api = new TelegramBotApi("http://127.0.0.1:1", TOKEN, Duration.ofSeconds(1), json);
        assertThatThrownBy(() -> api.call("getMe", Map.of()))
                .isInstanceOf(TelegramBotApi.TelegramApiException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("SECRET").contains("***"));
    }
}
