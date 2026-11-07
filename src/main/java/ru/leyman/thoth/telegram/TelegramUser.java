package ru.leyman.thoth.telegram;

/**
 * Пользователь Telegram, проверенный по подписи initData (или dev-пользователь в режиме разработки).
 * Кладётся в атрибут запроса {@link #ATTRIBUTE} фильтром {@link TelegramAuthFilter}.
 */
public record TelegramUser(long id, String username, String firstName, boolean dev) {

    public static final String ATTRIBUTE = "thoth.telegramUser";
}
