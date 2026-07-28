package com.juanluidos.ticketing.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Punto de la serie de precios de un producto. Se genera al validar el ticket. */
@Entity
@Table(name = "price_observation")
@Getter
@Setter
public class PriceObservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_product_id", nullable = false)
    private StoreProduct storeProduct;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "line_item_id")
    private LineItem lineItem;

    @Column(name = "observed_at", nullable = false)
    private LocalDateTime observedAt;

    @Column(nullable = false, precision = 12, scale = 4)
    private BigDecimal quantity;

    @Column(name = "line_total", nullable = false, precision = 12, scale = 4)
    private BigDecimal lineTotal;

    /** €/pieza. Es lo único que se puede guardar de una pieza de peso variable. */
    @Column(name = "price_per_piece", precision = 12, scale = 4)
    private BigDecimal pricePerPiece;

    /**
     * € por unidad base. Null si no es normalizable: pieza de peso variable, o
     * tamaño de envase aún sin rellenar.
     */
    @Column(name = "normalized_unit_price", precision = 16, scale = 6)
    private BigDecimal normalizedUnitPrice;

    @Column(name = "normalized_unit", length = 10)
    private String normalizedUnit;

    @Column(name = "is_promo", nullable = false)
    private boolean promo;

    /**
     * Falso para piezas de peso variable y para promociones: en el primer caso
     * la variación es peso, no subida; en el segundo no es precio de estantería.
     */
    @Column(name = "counts_for_increase", nullable = false)
    private boolean countsForIncrease = true;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
