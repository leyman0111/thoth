package ru.leyman.thoth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.math.BigDecimal;

/**
 * Доля гостя в позиции: shareRatio ∈ (0; 1], например 0.50 или 0.25.
 * Сумма долей по позиции ≤ 1 проверяется в сервисном слое (CHECK на агрегат в PostgreSQL не выразить).
 */
@Entity
@Table(name = "item_assignments",
        uniqueConstraints = @UniqueConstraint(name = "uq_assignments_item_guest", columnNames = {"item_id", "guest_id"}))
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ItemAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "item_id", nullable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Item item;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "guest_id", nullable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Guest guest;

    @Column(name = "share_ratio", nullable = false, precision = 3, scale = 2)
    private BigDecimal shareRatio;

    public ItemAssignment(Item item, Guest guest, BigDecimal shareRatio) {
        this.item = item;
        this.guest = guest;
        this.shareRatio = shareRatio;
    }
}
