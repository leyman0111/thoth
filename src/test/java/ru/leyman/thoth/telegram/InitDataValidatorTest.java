package ru.leyman.thoth.telegram;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InitDataValidatorTest {

    private static final String TOKEN = "123456789:AAH-test-token_ForUnitTests";
    private static final long AUTH_DATE = 1_793_260_000L;

    /**
     * Эталон сгенерирован независимой реализацией алгоритма из документации Telegram (Python, hmac/hashlib):
     * кириллица в имени, поле signature в data-check-string, hash — последним параметром.
     */
    private static final String VALID = "query_id=AAHdF6IQAAAAAN0XohDhrOrc"
            + "&user=%7B%22id%22%3A279058397%2C%22first_name%22%3A%22%D0%98%D0%BB%D1%8C%D0%BD%D1%83%D1%80%22"
            + "%2C%22last_name%22%3A%22%22%2C%22username%22%3A%22ilnur%22%2C%22language_code%22%3A%22ru%22"
            + "%2C%22allows_write_to_pm%22%3Atrue%7D"
            + "&auth_date=1793260000&signature=SiGnAtUrE_base64url-xyz"
            + "&hash=d234e3d78f83fc53f8ea62dad6da78320d65228d6bca02040b89478f4ec6fda3";

    private static InitDataValidator validator(String token, long nowEpochSecond) {
        return new InitDataValidator(token, Duration.ofHours(24), JsonMapper.builder().build(),
                Clock.fixed(Instant.ofEpochSecond(nowEpochSecond), ZoneOffset.UTC));
    }

    @Test
    void acceptsDataSignedByTelegram() {
        TelegramUser user = validator(TOKEN, AUTH_DATE + 60).validate(VALID);

        assertThat(user.id()).isEqualTo(279058397L);
        assertThat(user.username()).isEqualTo("ilnur");
        assertThat(user.firstName()).isEqualTo("Ильнур");
        assertThat(user.dev()).isFalse();
    }

    @Test
    void fieldOrderDoesNotMatter() {
        String reordered = "hash=" + VALID.substring(VALID.indexOf("&hash=") + 6) + "&"
                + VALID.substring(0, VALID.indexOf("&hash="));
        assertThat(validator(TOKEN, AUTH_DATE).validate(reordered).id()).isEqualTo(279058397L);
    }

    @Test
    void rejectsTamperedUserId() {
        assertInvalid(validator(TOKEN, AUTH_DATE), VALID.replace("279058397", "279058398"), "Подпись");
    }

    @Test
    void rejectsOtherBotToken() {
        assertInvalid(validator("987654321:other", AUTH_DATE), VALID, "Подпись");
    }

    @Test
    void rejectsRemovedSignatureField() {
        // signature входит в data-check-string: без него подпись не сходится
        assertInvalid(validator(TOKEN, AUTH_DATE), VALID.replace("&signature=SiGnAtUrE_base64url-xyz", ""), "Подпись");
    }

    @Test
    void rejectsExpiredData() {
        assertInvalid(validator(TOKEN, AUTH_DATE + Duration.ofHours(25).toSeconds()), VALID, "устарели");
    }

    @Test
    void rejectsDataFromTheFuture() {
        assertInvalid(validator(TOKEN, AUTH_DATE - 3600), VALID, "будущего");
    }

    @Test
    void rejectsDuplicatedField() {
        assertInvalid(validator(TOKEN, AUTH_DATE), VALID + "&user=%7B%22id%22%3A1%7D", "Повторяющееся");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "auth_date=1793260000", "garbage"})
    void rejectsMissingHash(String initData) {
        assertThatThrownBy(() -> validator(TOKEN, AUTH_DATE).validate(initData))
                .isInstanceOf(InitDataValidator.InvalidInitDataException.class);
    }

    @Test
    void notConfiguredWithoutToken() {
        InitDataValidator noToken = validator(" ", AUTH_DATE);
        assertThat(noToken.isConfigured()).isFalse();
        assertInvalid(noToken, VALID, "Токен");
    }

    private static void assertInvalid(InitDataValidator validator, String initData, String messagePart) {
        assertThatThrownBy(() -> validator.validate(initData))
                .isInstanceOf(InitDataValidator.InvalidInitDataException.class)
                .hasMessageContaining(messagePart);
    }
}
