package ru.leyman.thoth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * Пользователь Telegram. PK — telegramId, выдаётся Telegram, а не БД,
 * поэтому без @GeneratedValue: save() для нового id делает merge (SELECT + INSERT).
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

    @Id
    @Column(name = "telegram_id", nullable = false, updatable = false)
    private Long telegramId;

    @Column(name = "username", length = 32)
    private String username;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public User(Long telegramId, String username) {
        this.telegramId = telegramId;
        this.username = username;
    }
}
