package ru.leyman.thoth.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.leyman.thoth.domain.Session;
import ru.leyman.thoth.domain.SessionStatus;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface SessionRepository extends JpaRepository<Session, UUID> {

    /** Самая свежая сессия пользователя (индекс idx_sessions_user_created). */
    Optional<Session> findFirstByUserTelegramIdOrderByCreatedAtDesc(Long telegramId);

    Optional<Session> findFirstByUserTelegramIdAndStatusNotOrderByCreatedAtDesc(Long telegramId, SessionStatus status);

    /**
     * Удаление сессии одним SQL-запросом: items, guests и item_assignments
     * удаляет PostgreSQL через ON DELETE CASCADE, без загрузки сущностей в память.
     * clearAutomatically сбрасывает persistence context, чтобы в нём не остались «мёртвые» дочерние сущности.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from Session s where s.id = :id")
    int deleteCascadeById(@Param("id") UUID id);

    /** Очистка устаревших сессий (например, из @Scheduled-задачи). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from Session s where s.createdAt < :threshold")
    int deleteAllCreatedBefore(@Param("threshold") Instant threshold);

    @Modifying(clearAutomatically = true)
    @Query("update Session s set s.status = :status where s.id = :id")
    int updateStatus(@Param("id") UUID id, @Param("status") SessionStatus status);
}
