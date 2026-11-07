package ru.leyman.thoth.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.leyman.thoth.domain.Session;
import ru.leyman.thoth.domain.SessionStatus;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface SessionRepository extends JpaRepository<Session, UUID> {

    /** Сессия, только если она принадлежит пользователю: чужая сессия неотличима от несуществующей. */
    Optional<Session> findByIdAndUserTelegramId(UUID id, Long telegramId);

    /** То же с блокировкой строки сессии (SELECT ... FOR UPDATE): правки долей в сессии идут по очереди. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Session s where s.id = :id and s.user.telegramId = :telegramId")
    Optional<Session> findOwnedForUpdate(@Param("id") UUID id, @Param("telegramId") Long telegramId);

    /** Самая свежая сессия пользователя (индекс idx_sessions_user_created). */
    Optional<Session> findFirstByUserTelegramIdOrderByCreatedAtDesc(Long telegramId);

    /**
     * Атомарная смена статуса «если текущий статус равен expected».
     * Защищает от двух параллельных загрузок чека в одну сессию.
     *
     * @return 1 если статус сменился, 0 если кто-то успел раньше
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Session s set s.status = :next where s.id = :id and s.status = :expected")
    int compareAndSetStatus(@Param("id") UUID id,
                            @Param("expected") SessionStatus expected,
                            @Param("next") SessionStatus next);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Session s set s.status = :status where s.id = :id")
    int updateStatus(@Param("id") UUID id, @Param("status") SessionStatus status);

    /**
     * Удаление сессии одним SQL-запросом: items, guests и item_assignments
     * удаляет PostgreSQL через ON DELETE CASCADE, без загрузки сущностей в память.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from Session s where s.id = :id")
    int deleteCascadeById(@Param("id") UUID id);

    /** Очистка устаревших сессий (например, из @Scheduled-задачи). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from Session s where s.createdAt < :threshold")
    int deleteAllCreatedBefore(@Param("threshold") Instant threshold);
}
