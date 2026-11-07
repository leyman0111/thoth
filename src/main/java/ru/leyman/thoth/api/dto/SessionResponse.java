package ru.leyman.thoth.api.dto;

import ru.leyman.thoth.domain.SessionStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Полное состояние сессии для Mini App: позиции, гости, доли и посчитанные суммы. */
public record SessionResponse(
        UUID id,
        SessionStatus status,
        Instant createdAt,
        List<GuestResponse> guests,
        List<ItemResponse> items,
        SplitSummaryResponse summary
) {
}
