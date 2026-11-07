package ru.leyman.thoth.telegram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import ru.leyman.thoth.config.ThothProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Бот, который открывает Mini App:
 * <ul>
 *   <li>при старте ставит кнопку меню «Разделить чек» (web_app) и команду /start;</li>
 *   <li>на любое сообщение в личном чате отвечает приветствием с кнопкой открытия Mini App.</li>
 * </ul>
 * Обновления получает long polling'ом (getUpdates) — публичный адрес для webhook не нужен.
 * Сам Mini App при этом должен быть доступен по HTTPS (требование Telegram), см. thoth.telegram.web-app-url.
 * <p>
 * Работает только в одном экземпляре приложения: второй getUpdates с тем же токеном получит 409.
 */
@Component
public class TelegramBot implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(TelegramBot.class);

    static final String BUTTON_TEXT = "🧾 Разделить чек";
    static final String MENU_TEXT = "Разделить чек";
    static final String WELCOME = """
            Привет! Я помогу разделить счёт в ресторане.

            Сфотографируйте чек, отметьте, кто что ел, — и я посчитаю, сколько платит каждый.""";

    private static final int POLL_TIMEOUT_SECONDS = 30;
    private static final Duration RETRY_DELAY = Duration.ofSeconds(5);

    private final ThothProperties.Telegram props;
    private final JsonMapper jsonMapper;

    private volatile boolean running;
    private Thread worker;
    private TelegramBotApi api;
    private long offset;

    public TelegramBot(ThothProperties properties, JsonMapper jsonMapper) {
        this.props = properties.telegram();
        this.jsonMapper = jsonMapper;
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void start() {
        if (!props.botEnabled() || !StringUtils.hasText(props.botToken())) {
            log.info("Telegram-бот выключен (нет thoth.telegram.bot-token или bot-enabled=false)");
            return;
        }
        if (!StringUtils.hasText(props.webAppUrl()) || !props.webAppUrl().startsWith("https://")) {
            log.warn("Telegram-бот не запущен: thoth.telegram.web-app-url должен быть HTTPS-адресом Mini App "
                    + "(сейчас: «{}»). Для локальной отладки используйте туннель (cloudflared, ngrok)", props.webAppUrl());
            return;
        }
        api = new TelegramBotApi(props.apiBaseUrl(), props.botToken().strip(),
                Duration.ofSeconds(POLL_TIMEOUT_SECONDS + 15), jsonMapper);
        running = true;
        worker = Thread.ofVirtual().name("telegram-bot").start(this::run);
    }

    @Override
    public void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    // ---------------------------------------------------------------------- work

    private void run() {
        while (running && !configure()) {
            sleep(RETRY_DELAY);
        }
        log.info("Telegram-бот запущен, Mini App: {}", props.webAppUrl());
        while (running) {
            pollOnce();
        }
    }

    /** Убирает webhook (иначе getUpdates вернёт 409), ставит команду /start и кнопку меню. */
    boolean configure() {
        try {
            api.call("deleteWebhook", Map.of("drop_pending_updates", false));
            api.call("setMyCommands", Map.of("commands", List.of(
                    Map.of("command", "start", "description", "Разделить чек"))));
            api.call("setChatMenuButton", Map.of("menu_button", Map.of(
                    "type", "web_app",
                    "text", MENU_TEXT,
                    "web_app", Map.of("url", props.webAppUrl()))));
            return true;
        } catch (TelegramBotApi.TelegramApiException e) {
            log.error("Не удалось настроить бота: {}", e.getMessage());
            if (e.errorCode() == 401 || e.errorCode() == 404) {
                log.error("Проверьте TELEGRAM_BOT_TOKEN — Telegram его не принимает");
            }
            return false;
        }
    }

    void pollOnce() {
        try {
            JsonNode updates = api.call("getUpdates", Map.of(
                    "offset", offset,
                    "timeout", POLL_TIMEOUT_SECONDS,
                    "allowed_updates", List.of("message")));
            for (JsonNode update : updates.values()) {
                offset = Math.max(offset, update.path("update_id").asLong() + 1);
                try {
                    handle(update);
                } catch (RuntimeException e) {
                    log.warn("Ошибка обработки update {}: {}", update.path("update_id").asLong(), e.getMessage());
                }
            }
        } catch (TelegramBotApi.TelegramApiException e) {
            if (!running) {
                return;
            }
            if (e.errorCode() == 409) {
                log.warn("Этот токен уже читает другой экземпляр бота или установлен webhook: {}", e.getMessage());
            } else {
                log.warn("getUpdates: {}", e.getMessage());
            }
            sleep(RETRY_DELAY);
        }
    }

    /** Любое сообщение в личке → приветствие с кнопкой Mini App. В группах кнопки web_app не работают. */
    void handle(JsonNode update) {
        JsonNode message = update.path("message");
        if (message.isMissingNode() || !"private".equals(message.path("chat").path("type").asString())) {
            return;
        }
        long chatId = message.path("chat").path("id").asLong();
        api.call("sendMessage", Map.of(
                "chat_id", chatId,
                "text", WELCOME,
                "reply_markup", Map.of("inline_keyboard", List.of(List.of(
                        Map.of("text", BUTTON_TEXT, "web_app", Map.of("url", props.webAppUrl())))))));
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }

    // Для теста
    void useApi(TelegramBotApi api) {
        this.api = api;
    }
}
