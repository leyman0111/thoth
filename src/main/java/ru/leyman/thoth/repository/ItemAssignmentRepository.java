package ru.leyman.thoth.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.leyman.thoth.domain.ItemAssignment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ItemAssignmentRepository extends JpaRepository<ItemAssignment, Long> {

    /** Все доли сессии одним запросом (без N+1). */
    @Query("""
            select a from ItemAssignment a
            join fetch a.item i
            join fetch a.guest g
            where i.session.id = :sessionId
            order by i.id, g.id
            """)
    List<ItemAssignment> findAllBySessionIdFetched(@Param("sessionId") UUID sessionId);

    Optional<ItemAssignment> findByItemIdAndGuestId(Long itemId, Long guestId);

    /** Доли одной позиции в порядке добавления, с гостями (без N+1). */
    @Query("select a from ItemAssignment a join fetch a.guest where a.item.id = :itemId order by a.id")
    List<ItemAssignment> findAllByItemIdOrderByIdAsc(@Param("itemId") Long itemId);
}
