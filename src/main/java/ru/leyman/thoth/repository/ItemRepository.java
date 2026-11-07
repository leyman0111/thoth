package ru.leyman.thoth.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.leyman.thoth.domain.Item;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ItemRepository extends JpaRepository<Item, Long> {

    List<Item> findAllBySessionIdOrderByIdAsc(UUID sessionId);

    Optional<Item> findByIdAndSessionId(Long id, UUID sessionId);

    /** Перераспознавание чека: старые позиции и их доли удаляются каскадом в БД. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from Item i where i.session.id = :sessionId")
    int deleteAllBySessionId(@Param("sessionId") UUID sessionId);
}
