package ru.leyman.thoth.telegram;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.leyman.thoth.config.ThothProperties;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Аутентификация запросов к /api/**.
 * <p>
 * Mini App отправляет {@code Authorization: tma <Telegram.WebApp.initData>}; подпись проверяется
 * {@link InitDataValidator}, и пользователь попадает в атрибут запроса {@link TelegramUser#ATTRIBUTE}.
 * <p>
 * В режиме разработки ({@code thoth.telegram.dev-mode=true}) дополнительно принимается заголовок
 * {@code X-Telegram-User-Id} без подписи — для отладки в обычном браузере. На проде режим выключен.
 */
@Component
public class TelegramAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TelegramAuthFilter.class);

    public static final String SCHEME = "tma ";
    public static final String DEV_USER_HEADER = "X-Telegram-User-Id";
    public static final String DEV_USERNAME_HEADER = "X-Telegram-Username";

    private final InitDataValidator validator;
    private final JsonMapper jsonMapper;
    private final boolean devMode;

    public TelegramAuthFilter(InitDataValidator validator, JsonMapper jsonMapper, ThothProperties properties) {
        this.validator = validator;
        this.jsonMapper = jsonMapper;
        this.devMode = properties.telegram().devMode();
        if (!validator.isConfigured() && !devMode) {
            throw new IllegalStateException("Не задан токен бота (thoth.telegram.bot-token / TELEGRAM_BOT_TOKEN). "
                    + "Без него нельзя проверить пользователей Mini App. Для отладки в браузере включите "
                    + "THOTH_DEV_MODE=true");
        }
        if (devMode) {
            log.warn("Режим разработки: API принимает {} без подписи Telegram. Не включайте на проде", DEV_USER_HEADER);
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/")
                || HttpMethod.OPTIONS.matches(request.getMethod());   // CORS preflight
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        TelegramUser user;
        try {
            if (authorization != null && authorization.regionMatches(true, 0, SCHEME, 0, SCHEME.length())) {
                user = validator.validate(authorization.substring(SCHEME.length()).strip());
            } else if (devMode && request.getHeader(DEV_USER_HEADER) != null) {
                user = devUser(request);
            } else {
                unauthorized(response, "Откройте приложение из Telegram");
                return;
            }
        } catch (InitDataValidator.InvalidInitDataException e) {
            log.debug("Отклонён запрос {} {}: {}", request.getMethod(), request.getRequestURI(), e.getMessage());
            unauthorized(response, e.getMessage());
            return;
        }

        request.setAttribute(TelegramUser.ATTRIBUTE, user);
        chain.doFilter(request, response);
    }

    private static TelegramUser devUser(HttpServletRequest request) {
        try {
            long id = Long.parseLong(request.getHeader(DEV_USER_HEADER).strip());
            return new TelegramUser(id, request.getHeader(DEV_USERNAME_HEADER), null, true);
        } catch (NumberFormatException e) {
            throw new InitDataValidator.InvalidInitDataException("Некорректный " + DEV_USER_HEADER);
        }
    }

    private void unauthorized(HttpServletResponse response, String detail) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "about:blank");
        body.put("title", "Unauthorized");
        body.put("status", HttpStatus.UNAUTHORIZED.value());
        body.put("detail", detail);
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "tma");
        jsonMapper.writeValue(response.getWriter(), body);
    }
}
