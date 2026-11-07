package ru.leyman.thoth.api;

import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import ru.leyman.thoth.api.dto.AssignRequest;
import ru.leyman.thoth.api.dto.SessionResponse;
import ru.leyman.thoth.api.dto.SplitSummaryResponse;
import ru.leyman.thoth.service.ApiException;
import ru.leyman.thoth.service.BillSplitterService;
import ru.leyman.thoth.service.ReceiptService;
import ru.leyman.thoth.service.SessionService;
import ru.leyman.thoth.telegram.TelegramAuthFilter;
import ru.leyman.thoth.telegram.TelegramUser;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;

/**
 * REST API для Telegram Mini App.
 * <p>
 * Пользователь — из подписанных Telegram initData (заголовок {@code Authorization: tma ...}),
 * см. {@link TelegramAuthFilter}. Все операции с сессией доступны только её владельцу;
 * чужая сессия отвечает 404.
 * Ошибки возвращаются в формате application/problem+json.
 */
@RestController
@RequestMapping(path = "/api/session", produces = MediaType.APPLICATION_JSON_VALUE)
public class SessionController {

    private final SessionService sessionService;
    private final ReceiptService receiptService;
    private final BillSplitterService billSplitter;

    public SessionController(SessionService sessionService, ReceiptService receiptService,
                             BillSplitterService billSplitter) {
        this.sessionService = sessionService;
        this.receiptService = receiptService;
        this.billSplitter = billSplitter;
    }

    /** Новая сессия в статусе AWAITING_GUESTS. 201 + Location. */
    @PostMapping("/start")
    public ResponseEntity<SessionResponse> start(@RequestAttribute(TelegramUser.ATTRIBUTE) TelegramUser user) {
        SessionResponse session = sessionService.start(user.id(), user.username());
        var location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/session/{id}").buildAndExpand(session.id()).toUri();
        return ResponseEntity.created(location).body(session);
    }

    /** Тело: JSON-массив имён, например ["Аня", "Боря"]. Вызывается один раз: список гостей потом не меняется. */
    @PostMapping(path = "/{sessionId}/guests", consumes = MediaType.APPLICATION_JSON_VALUE)
    public SessionResponse addGuests(@RequestAttribute(TelegramUser.ATTRIBUTE) TelegramUser user,
                                     @PathVariable UUID sessionId,
                                     @RequestBody List<String> names) {
        return sessionService.addGuests(sessionId, user.id(), names);
    }

    /**
     * Фото чека в поле "file". Файл не сохраняется: поток сразу уходит в OpenRouter.
     * Запрос синхронный — ответ приходит после распознавания (обычно 5–30 с).
     */
    @PostMapping(path = "/{sessionId}/receipt", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public SessionResponse uploadReceipt(@RequestAttribute(TelegramUser.ATTRIBUTE) TelegramUser user,
                                         @PathVariable UUID sessionId,
                                         @RequestPart("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            throw ApiException.badRequest("Файл пустой");
        }
        try (InputStream in = file.getInputStream()) {
            return receiptService.recognize(sessionId, user.id(), in, file.getContentType());
        }
    }

    @GetMapping("/{sessionId}")
    public SessionResponse get(@RequestAttribute(TelegramUser.ATTRIBUTE) TelegramUser user,
                               @PathVariable UUID sessionId) {
        return sessionService.get(sessionId, user.id());
    }

    /**
     * {itemId, guestId, shareRatio}. Доли остальных гостей пересчитываются так, чтобы сумма по позиции
     * была ровно 1.00; остаток может уйти следующему гостю из списка. Возвращает всю сессию.
     */
    @PostMapping(path = "/{sessionId}/assign", consumes = MediaType.APPLICATION_JSON_VALUE)
    public SessionResponse assign(@RequestAttribute(TelegramUser.ATTRIBUTE) TelegramUser user,
                                  @PathVariable UUID sessionId,
                                  @Valid @RequestBody AssignRequest request) {
        return billSplitter.assign(sessionId, user.id(), request.itemId(), request.guestId(), request.shareRatio());
    }

    /** Финальный расчёт: Σ (цена × доля) по гостям с «умным округлением». Ничего не меняет в БД. */
    @PostMapping("/{sessionId}/recalculate")
    public SplitSummaryResponse recalculate(@RequestAttribute(TelegramUser.ATTRIBUTE) TelegramUser user,
                                            @PathVariable UUID sessionId) {
        return sessionService.recalculate(sessionId, user.id());
    }
}
