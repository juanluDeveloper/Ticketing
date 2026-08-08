package com.juanluidos.ticketing.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Artículo lógico dentro de UN súper. Es el nivel que acumula histórico de
 * precios y sobre el que se cuentan las subidas.
 */
@Entity
@Table(name = "store_product")
@Getter
@Setter
public class StoreProduct {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @Column(name = "canonical_name", nullable = false, length = 200)
    private String canonicalName;

    /** Nombre limpio escrito a mano. Es lo que hace usables los productos de Xinya. */
    @Column(name = "display_name", length = 200)
    private String displayName;

    /** Texto libre, p. ej. "西柚 = pomelo". Se teclea una vez y se reutiliza. */
    @Column(columnDefinition = "text")
    private String notes;

    @Column(length = 100)
    private String brand;

    /**
     * Tamaño del envase. Casi nunca viene en el ticket como campo aparte, pero
     * sí suele estar embebido en la descripción ("330ML", "1,10L", "180GR"), así
     * que el parser de tamaño lo prerrellena en la validación.
     */
    @Column(name = "package_size", precision = 12, scale = 4)
    private BigDecimal packageSize;

    @Column(name = "package_unit", length = 10)
    private String packageUnit;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private Dimension dimension;

    @Enumerated(EnumType.STRING)
    @Column(name = "sold_by", nullable = false, length = 20)
    private SoldBy soldBy = SoldBy.PACKAGE;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "comparable_group_id")
    private ComparableGroup comparableGroup;

    /**
     * Letra de IVA observada habitualmente. Alimenta la heurística H1: si llega
     * una línea de este producto con otra letra, lo más probable es que las
     * columnas del ticket se hayan desalineado.
     */
    @Column(name = "usual_tax_letter", length = 1)
    private String usualTaxLetter;

    /**
     * Precio por unidad leído en el mostrador, para los productos cuyo ticket
     * nunca dará lo suficiente para calcularlo: el tubo de pota imprime nombre e
     * importe, sin peso y sin €/kg.
     *
     * <p>No es una observación de precio. No sale de un ticket, no entra en la
     * serie del histórico y no cuenta subidas: solo lo usa el comparador como
     * respaldo, etiquetado como declarado. Ya viene en unidad canónica.
     */
    @Column(name = "declared_unit_price", precision = 12, scale = 4)
    private BigDecimal declaredUnitPrice;

    /** kg, L o ud. */
    @Column(name = "declared_unit", length = 10)
    private String declaredUnit;

    /** Cuándo se leyó ese precio, que es lo que lo deja envejecer como los demás. */
    @Column(name = "declared_at")
    private LocalDateTime declaredAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
