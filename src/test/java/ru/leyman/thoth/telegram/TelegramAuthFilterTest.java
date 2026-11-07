package ru.leyman.thoth.telegram;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import ru.leyman.thoth.config.ThothProperties;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TelegramAuthFilterTest {

    private static final String TOKEN = "123456789:AAH-test-token_ForUnitTests";
    private static final String VALID = "query_id=AAHdF6IQAAAAAN0XohDhrOrc"
            + "&user=%7B%22id%22%3A279058397%2C%22first_name%22%3A%22%D0%98%D0%BB%D1%8C%D0%BD%D1%83%D1%80%22"
            + "%2C%22last_name%22%3A%22%22%2C%22username%22%3A%22ilnur%22%2C%22language_code%22%3A%22ru%22"
            + "%2C%22allows_write_to_pm%22%3Atrue%7D"
            + "&auth_date=1793260000&signature=SiGnAtUrE_base64url-xyz"
            + "&hash=d234e3d78f83fc53f8ea62dad6da78320d65228d6bca02040b89478f4ec6fda3";

    private final JsonMapper json = JsonMapper.builder().build();

    private TelegramAuthFilter filter(String token, boolean devMode) {
        var validator = new InitDataValidator(token, Duration.ofHours(24), json,
                Clock.fixed(Instant.ofEpochSecond(1_793_260_100L), ZoneOffset.UTC));
        var props = new ThothProperties(null, null, null,
                new ThothProperties.Telegram(token, null, Duration.ofHours(24), devMode, false, null));
        return new TelegramAuthFilter(validator, json, props);
    }

    private record Result(MockHttpServletResponse response, TelegramUser user, boolean passed) {
    }

    private Result run(TelegramAuthFilter filter, MockHttpServletRequest request) throws Exception {
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        HttpServletRequest passed = (HttpServletRequest) chain.getRequest();
        return new Result(response,
                passed == null ? null : (TelegramUser) passed.getAttribute(TelegramUser.ATTRIBUTE),
                passed != null);
    }

    private static MockHttpServletRequest api(String method) {
        var request = new MockHttpServletRequest(method, "/api/session/start");
        request.setRequestURI("/api/session/start");
        return request;
    }

    @Test
    void signedInitDataPasses() throws Exception {
        var request = api("POST");
        request.addHeader("Authorization", "tma " + VALID);

        Result result = run(filter(TOKEN, false), request);

        assertThat(result.passed()).isTrue();
        assertThat(result.user().id()).isEqualTo(279058397L);
        assertThat(result.user().username()).isEqualTo("ilnur");
    }

    @Test
    void forgedHeaderIsRejectedInProduction() throws Exception {
        var request = api("POST");
        request.addHeader("X-Telegram-User-Id", "279058397");

        Result result = run(filter(TOKEN, false), request);

        assertThat(result.passed()).isFalse();
        assertThat(result.response().getStatus()).isEqualTo(401);
        assertThat(result.response().getContentType()).startsWith("application/problem+json");
        assertThat(result.response().getContentAsString()).contains("Откройте приложение из Telegram");
    }

    @Test
    void tamperedInitDataIsRejected() throws Exception {
        var request = api("GET");
        request.addHeader("Authorization", "tma " + VALID.replace("279058397", "1"));

        Result result = run(filter(TOKEN, false), request);

        assertThat(result.response().getStatus()).isEqualTo(401);
        assertThat(result.response().getContentAsString()).contains("Подпись");
    }

    @Test
    void devHeaderAcceptedOnlyInDevMode() throws Exception {
        var request = api("POST");
        request.addHeader("X-Telegram-User-Id", "42");
        request.addHeader("X-Telegram-Username", "dev");

        Result result = run(filter(null, true), request);

        assertThat(result.passed()).isTrue();
        assertThat(result.user().id()).isEqualTo(42L);
        assertThat(result.user().dev()).isTrue();
    }

    @Test
    void staticFilesAndPreflightAreNotFiltered() throws Exception {
        var page = new MockHttpServletRequest("GET", "/index.html");
        page.setRequestURI("/index.html");
        assertThat(run(filter(TOKEN, false), page).passed()).isTrue();

        var preflight = api("OPTIONS");
        assertThat(run(filter(TOKEN, false), preflight).passed()).isTrue();
    }

    @Test
    void startupFailsWithoutTokenOutsideDevMode() {
        assertThatThrownBy(() -> filter(null, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TELEGRAM_BOT_TOKEN");
    }
}
