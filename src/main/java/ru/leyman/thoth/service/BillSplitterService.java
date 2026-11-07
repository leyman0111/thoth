package ru.leyman.thoth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.leyman.thoth.api.dto.SessionResponse;
import ru.leyman.thoth.domain.Guest;
import ru.leyman.thoth.domain.Item;
import ru.leyman.thoth.domain.ItemAssignment;
import ru.leyman.thoth.domain.Session;
import ru.leyman.thoth.domain.SessionStatus;
import ru.leyman.thoth.repository.GuestRepository;
import ru.leyman.thoth.repository.ItemAssignmentRepository;
import ru.leyman.thoth.repository.ItemRepository;
import ru.leyman.thoth.repository.SessionRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Логика разделения счёта: доли гостей по позициям и финальный расчёт.
 * <p>
 * Перераспределение долей — в {@link ShareRebalancer} (чистая функция); здесь — загрузка и сохранение.
 * Изменения долей выполняются под блокировкой строки сессии (SELECT ... FOR UPDATE), поэтому
 * параллельные правки не нарушают инвариант «сумма долей по позиции = 1.00».
 */
@Service
public class BillSplitterService {

    private static final Logger log = LoggerFactory.getLogger(BillSplitterService.class);

    static final BigDecimal MAX_ROUNDING_DIFF = new BigDecimal("0.02");

    private final SessionRepository sessionRepository;
    private final ItemRepository itemRepository;
    private final GuestRepository guestRepository;
    private final ItemAssignmentRepository assignmentRepository;
    private final SessionService sessionService;

    public BillSplitterService(SessionRepository sessionRepository,
                               ItemRepository itemRepository,
                               GuestRepository guestRepository,
                               ItemAssignmentRepository assignmentRepository,
                               SessionService sessionService) {
        this.sessionRepository = sessionRepository;
        this.itemRepository = itemRepository;
        this.guestRepository = guestRepository;
        this.assignmentRepository = assignmentRepository;
        this.sessionService = sessionService;
    }

    // =========================================================================== доли

    /**
     * Ставит гостю долю на позиции и перераспределяет остальные (см. {@link ShareRebalancer}).
     * shareRatio округляется до 2 знаков HALF_UP, затем проверяется диапазон [0; 1].
     * null — гость добавляется к позиции с долей 0.
     *
     * @return обновлённая сессия: могли измениться доли других гостей и появиться новые строки
     */
    @Transactional
    public SessionResponse assign(UUID sessionId, long userId, long itemId, long guestId, BigDecimal shareRatio) {
        BigDecimal share = normalizeShare(shareRatio);

        Session session = sessionRepository.findOwnedForUpdate(sessionId, userId)
                .orElseThrow(() -> ApiException.notFound("Сессия не найдена"));
        if (session.getStatus() != SessionStatus.READY) {
            throw ApiException.conflict("Назначать позиции можно после распознавания чека");
        }
        Item item = itemRepository.findByIdAndSessionId(itemId, sessionId)
                .orElseThrow(() -> ApiException.notFound("Позиция " + itemId + " не найдена в сессии"));
        List<Long> sessionGuests = guestRepository.findAllBySessionIdOrderByCreatedAtAscIdAsc(sessionId).stream()
                .map(Guest::getId).toList();
        if (!sessionGuests.contains(guestId)) {
            throw ApiException.notFound("Гость " + guestId + " не найден в сессии");
        }

        List<ItemAssignment> assignments = assignmentRepository.findAllByItemIdOrderByIdAsc(itemId);
        List<ShareRebalancer.Row> current = assignments.stream()
                .map(a -> new ShareRebalancer.Row(a.getGuest().getId(), a.getShareRatio()))
                .toList();

        List<ShareRebalancer.Row> updated = ShareRebalancer.setShare(current, sessionGuests, guestId, share);
        save(item, assignments, updated);
        return sessionService.get(sessionId, userId);
    }

    /** Правило 7: округление до сотых HALF_UP, затем проверка 0 ≤ x ≤ 1. */
    static BigDecimal normalizeShare(BigDecimal raw) {
        if (raw == null) {
            return BigDecimal.ZERO.setScale(2);
        }
        BigDecimal share = raw.setScale(2, RoundingMode.HALF_UP);
        if (share.signum() < 0 || share.compareTo(BigDecimal.ONE) > 0) {
            throw ApiException.badRequest("Доля должна быть от 0 до 1, получено " + raw.toPlainString());
        }
        return share;
    }

    /** Строки не удаляются (доля 0 остаётся): только обновление существующих и вставка новых. */
    private void save(Item item, List<ItemAssignment> assignments, List<ShareRebalancer.Row> updated) {
        Map<Long, ItemAssignment> existing = assignments.stream()
                .collect(Collectors.toMap(a -> a.getGuest().getId(), Function.identity()));
        for (ShareRebalancer.Row row : updated) {
            ItemAssignment assignment = existing.get(row.guestId());
            if (assignment != null) {
                assignment.setShareRatio(row.share());
            } else {
                assignmentRepository.save(new ItemAssignment(item, guestRepository.getReferenceById(row.guestId()), row.share()));
            }
        }
        assignmentRepository.flush();
    }

    // ==================================================================== финальный расчёт

    @Transactional(readOnly = true)
    public FinalSplit finalCalculation(UUID sessionId, long userId) {
        sessionService.requireOwned(sessionId, userId);
        return calculate(
                itemRepository.findAllBySessionIdOrderByIdAsc(sessionId).stream()
                        .map(i -> new ItemLine(i.getId(), i.getPrice())).toList(),
                guestRepository.findAllBySessionIdOrderByCreatedAtAscIdAsc(sessionId).stream()
                        .map(Guest::getId).toList(),
                assignmentRepository.findAllBySessionIdFetched(sessionId).stream()
                        .map(a -> new Share(a.getItem().getId(), a.getGuest().getId(), a.getShareRatio())).toList());
    }

    public record ItemLine(long itemId, BigDecimal price) {
    }

    public record Share(long itemId, long guestId, BigDecimal ratio) {
    }

    /**
     * @param guestTotals     guestId → к оплате, в сумме ровно assignedTotal
     * @param assignedTotal   сумма цен позиций, у которых есть доли
     * @param unassignedItems позиции, которые никому не назначены
     * @param roundingDiff    сколько копеек добавлено (или снято) гостю с наибольшей долей
     * @param roundingGuestId кому добавлена разница, null если разницы не было
     */
    public record FinalSplit(Map<Long, BigDecimal> guestTotals,
                             BigDecimal receiptTotal,
                             BigDecimal assignedTotal,
                             BigDecimal unassignedTotal,
                             List<Long> unassignedItems,
                             BigDecimal roundingDiff,
                             Long roundingGuestId) {
        public boolean complete() {
            return unassignedItems.isEmpty() && receiptTotal.signum() > 0;
        }
    }

    /**
     * Правило 8: для каждого гостя Σ (цена позиции × его доля), округление до копеек HALF_UP.
     * Правило 9: разница между суммой округлённых итогов и суммой назначенных позиций
     * добавляется гостю с наибольшей долей счёта (наибольшей неокруглённой суммой;
     * при равенстве — добавленному раньше). Итоги гостей всегда сходятся с чеком до копейки.
     */
    public static FinalSplit calculate(List<ItemLine> items, Collection<Long> guestIds, List<Share> shares) {
        BigDecimal zero = BigDecimal.ZERO.setScale(2);
        Map<Long, BigDecimal> exact = new LinkedHashMap<>();
        guestIds.forEach(id -> exact.put(id, BigDecimal.ZERO));

        Map<Long, BigDecimal> prices = items.stream()
                .collect(Collectors.toMap(ItemLine::itemId, i -> i.price().setScale(2, RoundingMode.HALF_UP),
                        (a, b) -> a, LinkedHashMap::new));
        Set<Long> assignedItems = shares.stream().map(Share::itemId).collect(Collectors.toSet());

        for (Share share : shares) {
            BigDecimal price = prices.get(share.itemId());
            if (price != null) {
                exact.merge(share.guestId(), price.multiply(share.ratio()), BigDecimal::add);
            }
        }

        Map<Long, BigDecimal> totals = new LinkedHashMap<>();
        exact.forEach((guestId, amount) -> totals.put(guestId, amount.setScale(2, RoundingMode.HALF_UP)));

        BigDecimal receiptTotal = prices.values().stream().reduce(zero, BigDecimal::add);
        BigDecimal assignedTotal = prices.entrySet().stream()
                .filter(e -> assignedItems.contains(e.getKey()))
                .map(Map.Entry::getValue)
                .reduce(zero, BigDecimal::add);
        List<Long> unassigned = prices.keySet().stream().filter(id -> !assignedItems.contains(id)).toList();

        // Правило 9: «умное округление»
        BigDecimal diff = assignedTotal.subtract(totals.values().stream().reduce(zero, BigDecimal::add));
        Long roundingGuest = null;
        if (diff.signum() != 0 && !totals.isEmpty()) {
            roundingGuest = exact.entrySet().stream()
                    .max(Comparator.comparing(Map.Entry<Long, BigDecimal>::getValue))
                    .map(Map.Entry::getKey)
                    .orElseThrow();
            totals.merge(roundingGuest, diff, BigDecimal::add);
            if (diff.abs().compareTo(MAX_ROUNDING_DIFF) > 0) {
                log.warn("Погрешность округления {} больше 2 копеек (гостей: {})", diff, totals.size());
            }
        }

        return new FinalSplit(totals, receiptTotal, assignedTotal, receiptTotal.subtract(assignedTotal),
                unassigned, diff, roundingGuest);
    }

    /** Сумма гостя по одной позиции для отображения (без «умного округления»). */
    public static BigDecimal amountFor(BigDecimal price, BigDecimal share) {
        return price.multiply(share).setScale(2, RoundingMode.HALF_UP);
    }

}
