package com.juanluidos.ticketing.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Precio por unidad leído en el cartel del súper, con el día en que se leyó.
 *
 * <p>Existe para los productos cuyo ticket no permitirá calcular el precio por
 * unidad nunca: el tubo de pota sale con nombre e importe, sin peso y sin €/kg.
 *
 * <p>No es una {@link PriceObservation} y no se mezcla con ellas. Aquella sale
 * de un ticket y prueba lo que pagaste; esta sale de mirar una etiqueta y solo
 * la usa el comparador. Pero sí es una serie: el cartel también cambia, y ver
 * que la pota pasó de 15 a 16 €/kg es justo lo que se quiere de ella.
 */
@Entity
@Table(name = "declared_price")
@Getter
@Setter
public class DeclaredPrice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_product_id", nullable = false)
    private StoreProduct storeProduct;

    /** Ya en unidad canónica: kg, L o ud. */
    @Column(name = "unit_price", nullable = false, precision = 12, scale = 4)
    private BigDecimal unitPrice;

    @Column(nullable = false, length = 10)
    private String unit;

    /** El día del cartel, que puede no ser hoy: se admite registrar hacia atrás. */
    @Column(name = "declared_at", nullable = false)
    private LocalDate declaredAt;

    @Column(length = 200)
    private String note;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
