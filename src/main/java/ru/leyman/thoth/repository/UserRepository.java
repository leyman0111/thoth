package ru.leyman.thoth.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.leyman.thoth.domain.User;

public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * Создаёт пользователя или обновляет username одним атомарным запросом.
     * Безопасно при параллельных запросах от одного пользователя (в отличие от findById + save).
     */
    @Modifying
    @Query(value = """
            insert into users (telegram_id, username)
            values (:telegramId, :username)
            on conflict (telegram_id)
                do update set username = coalesce(excluded.username, users.username)
            """, nativeQuery = true)
    void upsert(@Param("telegramId") long telegramId, @Param("username") String username);
}
