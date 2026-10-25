package ru.leyman.thoth.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.leyman.thoth.domain.ItemAssignment;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ItemAssignmentRepository extends JpaRepository<ItemAssignment, Long> {

    /** Все доли сессии одним запросом (без N+1) — для расчёта итогов в Mini App. */
    @Query("""
            select a from ItemAssignment a
            join fetch a.item i
            join fetch a.guest g
            where i.session.id = :sessionId
            order by i.id, g.id
            """)
    List<ItemAssignment> findAllBySessionIdFetched(@Param("sessionId") UUID sessionId);

    Optional<ItemAssignment> findByItemIdAndGuestId(Long itemId, Long guestId);

    /** Уже распределённая доля позиции — для проверки, что сумма не превысит 1.00. */
    @Query("select coalesce(sum(a.shareRatio), 0) from ItemAssignment a where a.item.id = :itemId")
    BigDecimal sumShareByItemId(@Param("itemId") Long itemId);
}
