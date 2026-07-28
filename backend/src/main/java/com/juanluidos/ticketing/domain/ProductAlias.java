package com.juanluidos.ticketing.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Cadena cruda con la que un producto ha aparecido en algún ticket.
 *
 * <p>Los súper abrevian distinto y truncan a ancho fijo, así que un producto
 * acumula varias. Cada confirmación en la pantalla de validación añade una, y
 * el matcher mejora solo.
 */
@Entity
@Table(name = "product_alias")
@Getter
@Setter
public class ProductAlias {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_product_id", nullable = false)
    private StoreProduct storeProduct;

    /** Desnormalizado para filtrar por súper sin join en el camino caliente del matcher. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @Column(name = "raw_text", nullable = false, length = 300)
    private String rawText;

    /** Mayúsculas, sin acentos, espacios colapsados. Sobre esto va la pasada exacta. */
    @Column(name = "normalized_text", nullable = false, length = 300)
    private String normalizedText;

    /**
     * Cola en alfabeto latino de las descripciones de Xinya. pg_trgm rinde mal
     * sobre chino, así que la similitud se calcula aquí y el chino se reserva
     * para la coincidencia exacta.
     */
    @Column(name = "latin_text", length = 300)
    private String latinText;

    @Column(name = "times_seen", nullable = false)
    private Integer timesSeen = 1;

    @Column(name = "first_seen_at", nullable = false)
    private LocalDateTime firstSeenAt = LocalDateTime.now();

    @Column(name = "last_seen_at", nullable = false)
    private LocalDateTime lastSeenAt = LocalDateTime.now();
}
