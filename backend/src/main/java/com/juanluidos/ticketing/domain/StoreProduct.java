package com.juanluidos.ticketing.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

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
     * Precios leídos en el cartel, para los productos cuyo ticket nunca dará lo
     * suficiente para calcularlos. Es una serie, no un dato suelto: ver que la
     * pota pasó de 15 a 16 €/kg es justo lo que se busca. Ver
     * {@link DeclaredPrice}.
     */
    @OneToMany(mappedBy = "storeProduct", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("declaredAt ASC")
    private List<DeclaredPrice> declaredPrices = new ArrayList<>();

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
