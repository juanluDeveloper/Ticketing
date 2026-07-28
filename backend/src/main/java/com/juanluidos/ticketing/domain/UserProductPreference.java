package com.juanluidos.ticketing.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Preferencia de un usuario dentro de un grupo comparable (mecanismo A).
 *
 * <p>El comparador resta la prima al preferido antes de ordenar, y enseña
 * siempre las dos cosas: el más barato objetivo y la elección ajustada, con lo
 * que cuesta la diferencia. Es por-usuario para que las preferencias de dos
 * personas no se pisen.
 */
@Entity
@Table(name = "user_product_preference")
@Getter
@Setter
public class UserProductPreference {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "comparable_group_id", nullable = false)
    private ComparableGroup comparableGroup;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "preferred_store_product_id", nullable = false)
    private StoreProduct preferredStoreProduct;

    @Enumerated(EnumType.STRING)
    @Column(name = "margin_type", nullable = false, length = 5)
    private MarginType marginType = MarginType.ABS;

    /** En la unidad de comparación del grupo si es ABS (€/kg, €/L, €/ud); en % si es PCT. */
    @Column(name = "margin_value", nullable = false, precision = 12, scale = 4)
    private BigDecimal marginValue = BigDecimal.ZERO;

    @Column(length = 300)
    private String note;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
