package ru.leyman.thoth.api.dto;

import java.math.BigDecimal;

/** Гость: на скольких позициях у него ненулевая доля и сколько он платит. */
public record GuestResponse(Long id, String name, int itemsCount, BigDecimal total) {
}
