package ru.leyman.thoth.domain;

/**
 * Жизненный цикл сессии разделения чека.
 * Значения хранятся строкой (см. CHECK chk_sessions_status в V1__init_schema.sql) —
 * при добавлении статуса нужна новая миграция, меняющая CHECK.
 */
public enum SessionStatus {
    AWAITING_GUESTS,
    AWAITING_PHOTO,
    PROCESSING,
    READY
}
