package ru.leyman.thoth.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.leyman.thoth.api.dto.GuestResponse;
import ru.leyman.thoth.api.dto.ItemResponse;
import ru.leyman.thoth.api.dto.SessionResponse;
import ru.leyman.thoth.api.dto.SplitSummaryResponse;
import ru.leyman.thoth.domain.Guest;
import ru.leyman.thoth.domain.Item;
import ru.leyman.thoth.domain.ItemAssignment;
import ru.leyman.thoth.domain.Session;
import ru.leyman.thoth.domain.SessionStatus;
import ru.leyman.thoth.repository.GuestRepository;
import ru.leyman.thoth.repository.ItemAssignmentRepository;
import ru.leyman.thoth.repository.ItemRepository;
import ru.leyman.thoth.repository.SessionRepository;
import ru.leyman.thoth.repository.UserRepository;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class SessionService {

    static final int MAX_GUESTS = 30;
    static final int MAX_GUEST_NAME = 100;

    private final UserRepository userRepository;
    private final SessionRepository sessionRepository;
    private final GuestRepository guestRepository;
    private final ItemRepository itemRepository;
    private final ItemAssignmentRepository assignmentRepository;

    public SessionService(UserRepository userRepository,
                          SessionRepository sessionRepository,
                          GuestRepository guestRepository,
                          ItemRepository itemRepository,
                          ItemAssignmentRepository assignmentRepository) {
        this.userRepository = userRepository;
        this.sessionRepository = sessionRepository;
        this.guestRepository = guestRepository;
        this.itemRepository = itemRepository;
        this.assignmentRepository = assignmentRepository;
    }

    // ------------------------------------------------------------------ start

    @Transactional
    public SessionResponse start(long userId, String username) {
        userRepository.upsert(userId, normalizeUsername(username));
        Session session = sessionRepository.saveAndFlush(new Session(userRepository.getReferenceById(userId)));
        return buildView(session, List.of(), List.of(), List.of());
    }

    // ----------------------------------------------------------------- guests

    /**
     * Задаёт список гостей. Вызывается один раз в начале: количество и порядок гостей
     * дальше не меняются (порядок определяет, кто получает остаток доли). Переводит сессию в AWAITING_PHOTO.
     */
    @Transactional
    public SessionResponse addGuests(UUID sessionId, long userId, List<String> names) {
        Session session = sessionRepository.findOwnedForUpdate(sessionId, userId)
                .orElseThrow(() -> ApiException.notFound("Сессия не найдена"));
        if (session.getStatus() != SessionStatus.AWAITING_GUESTS) {
            throw ApiException.conflict("Список гостей задаётся один раз в начале сессии и больше не меняется");
        }

        List<String> cleaned = validateGuestNames(names);
        if (cleaned.size() > MAX_GUESTS) {
            throw ApiException.badRequest("В сессии может быть не больше " + MAX_GUESTS + " гостей");
        }

        guestRepository.saveAll(cleaned.stream().map(name -> new Guest(session, name)).toList());
        session.setStatus(SessionStatus.AWAITING_PHOTO);
        guestRepository.flush();
        return load(session);
    }

    private static List<String> validateGuestNames(List<String> names) {
        if (names == null || names.isEmpty()) {
            throw ApiException.badRequest("Передайте непустой массив имён гостей");
        }
        List<String> result = new ArrayList<>(names.size());
        Set<String> seen = new HashSet<>();
        for (String raw : names) {
            String name = raw == null ? "" : raw.strip().replaceAll("\\s+", " ");
            if (name.isEmpty()) {
                throw ApiException.badRequest("Имя гостя не может быть пустым");
            }
            if (name.length() > MAX_GUEST_NAME) {
                throw ApiException.badRequest("Имя гостя длиннее " + MAX_GUEST_NAME + " символов");
            }
            if (!seen.add(nameKey(name))) {
                throw ApiException.badRequest("Имя «" + name + "» повторяется в запросе");
            }
            result.add(name);
        }
        return result;
    }

    // ---------------------------------------------------------------- receipt (вызывается из ReceiptService)

    /**
     * Шаг 1 загрузки чека: проверяет владельца и атомарно ставит PROCESSING.
     *
     * @return статус до начала обработки — в него сессия вернётся при ошибке распознавания
     */
    @Transactional
    public SessionStatus beginProcessing(UUID sessionId, long userId) {
        Session session = requireOwned(sessionId, userId);
        SessionStatus current = session.getStatus();
        switch (current) {
            case AWAITING_GUESTS -> throw ApiException.conflict("Сначала добавьте гостей");
            case PROCESSING -> throw ApiException.conflict("Чек уже распознаётся");
            case AWAITING_PHOTO, READY -> { /* можно загружать; из READY — повторное распознавание */ }
        }
        if (sessionRepository.compareAndSetStatus(sessionId, current, SessionStatus.PROCESSING) == 0) {
            throw ApiException.conflict("Чек уже распознаётся");
        }
        return current;
    }

    /** Шаг 3 (успех): заменяет позиции распознанными и ставит READY. Старые доли удаляются каскадом. */
    @Transactional
    public void completeProcessing(UUID sessionId, List<ReceiptItem> items) {
        itemRepository.deleteAllBySessionId(sessionId);
        Session ref = sessionRepository.getReferenceById(sessionId);
        itemRepository.saveAll(items.stream()
                .map(i -> new Item(ref, i.name(), i.price(), i.currency()))
                .toList());
        itemRepository.flush();
        sessionRepository.updateStatus(sessionId, SessionStatus.READY);
    }

    /** Шаг 3 (ошибка): возвращает статус, который был до загрузки. Старые позиции не тронуты. */
    @Transactional
    public void abortProcessing(UUID sessionId, SessionStatus previous) {
        sessionRepository.compareAndSetStatus(sessionId, SessionStatus.PROCESSING, previous);
    }

    public record ReceiptItem(String name, BigDecimal price, String currency) {
    }

    // ------------------------------------------------------------------- read

    @Transactional(readOnly = true)
    public SessionResponse get(UUID sessionId, long userId) {
        return load(requireOwned(sessionId, userId));
    }

    /** Финальный расчёт по текущим долям (BillSplitterService.calculate). Ничего не меняет в БД. */
    @Transactional(readOnly = true)
    public SplitSummaryResponse recalculate(UUID sessionId, long userId) {
        return load(requireOwned(sessionId, userId)).summary();
    }

    private SessionResponse load(Session session) {
        UUID id = session.getId();
        return buildView(session,
                itemRepository.findAllBySessionIdOrderByIdAsc(id),
                guestRepository.findAllBySessionIdOrderByCreatedAtAscIdAsc(id),
                assignmentRepository.findAllBySessionIdFetched(id));
    }

    private SessionResponse buildView(Session session, List<Item> items, List<Guest> guests,
                                      List<ItemAssignment> assignments) {
        BillSplitterService.FinalSplit split = BillSplitterService.calculate(
                items.stream().map(i -> new BillSplitterService.ItemLine(i.getId(), i.getPrice())).toList(),
                guests.stream().map(Guest::getId).toList(),
                assignments.stream()
                        .map(a -> new BillSplitterService.Share(a.getItem().getId(), a.getGuest().getId(), a.getShareRatio()))
                        .toList());

        Map<Long, Guest> guestById = guests.stream().collect(Collectors.toMap(Guest::getId, g -> g));
        Map<Long, Long> itemsPerGuest = assignments.stream()
                .filter(a -> a.getShareRatio().signum() > 0)
                .collect(Collectors.groupingBy(a -> a.getGuest().getId(), Collectors.counting()));
        // Порядок строк как в ShareRebalancer: в порядке добавления гостей на позицию
        Map<Long, List<ItemAssignment>> byItem = assignments.stream()
                .sorted(Comparator.comparing(ItemAssignment::getId))
                .collect(Collectors.groupingBy(a -> a.getItem().getId(), LinkedHashMap::new, Collectors.toList()));

        List<GuestResponse> guestViews = guests.stream()
                .map(g -> new GuestResponse(g.getId(), g.getName(),
                        itemsPerGuest.getOrDefault(g.getId(), 0L).intValue(),
                        split.guestTotals().get(g.getId())))
                .toList();

        List<ItemResponse> itemViews = items.stream().map(i -> {
            List<ItemResponse.Assignment> rows = byItem.getOrDefault(i.getId(), List.of()).stream()
                    .map(a -> {
                        Guest g = guestById.get(a.getGuest().getId());
                        return new ItemResponse.Assignment(g.getId(), g.getName(),
                                a.getShareRatio(), BillSplitterService.amountFor(i.getPrice(), a.getShareRatio()));
                    })
                    .toList();
            return new ItemResponse(i.getId(), i.getName(), i.getPrice(), i.getCurrency(),
                    rows.isEmpty() ? ItemResponse.Status.UNASSIGNED : ItemResponse.Status.ASSIGNED, rows);
        }).toList();

        String currency = items.isEmpty() ? null : items.getFirst().getCurrency();
        SplitSummaryResponse summary = new SplitSummaryResponse(
                currency,
                split.receiptTotal(),
                split.assignedTotal(),
                split.unassignedTotal(),
                split.complete(),
                split.unassignedItems(),
                split.roundingDiff(),
                split.roundingGuestId(),
                guests.stream()
                        .map(g -> new SplitSummaryResponse.GuestTotal(g.getId(), g.getName(),
                                split.guestTotals().get(g.getId())))
                        .toList());

        return new SessionResponse(session.getId(), session.getStatus(), session.getCreatedAt(),
                guestViews, itemViews, summary);
    }

    // ---------------------------------------------------------------- helpers

    Session requireOwned(UUID sessionId, long userId) {
        return sessionRepository.findByIdAndUserTelegramId(sessionId, userId)
                .orElseThrow(() -> ApiException.notFound("Сессия не найдена"));
    }

    private static String nameKey(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static String normalizeUsername(String username) {
        if (username == null || username.isBlank()) {
            return null;
        }
        String u = username.strip();
        if (u.startsWith("@")) {
            u = u.substring(1);
        }
        return u.length() > 32 ? u.substring(0, 32) : u;
    }
}
