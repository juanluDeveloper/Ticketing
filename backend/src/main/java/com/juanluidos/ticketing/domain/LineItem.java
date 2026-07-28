package com.juanluidos.ticketing.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/** Línea del recibo tal cual se imprimió. Verdad inmutable una vez validada. */
@Entity
@Table(name = "line_item")
@Getter
@Setter
public class LineItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ticket_id", nullable = false)
    private Ticket ticket;

    @Column(name = "line_no", nullable = false)
    private Integer lineNo;

    /**
     * La fila física transcrita literal, sin interpretar (etapa 1 de la
     * extracción). El resto de campos se derivan de esta cadena, así que
     * descripción e importe salen de la misma fila por construcción; extraer
     * campos sueltos de la imagen es lo que permite que un precio se enganche a
     * la descripción de al lado.
     */
    @Column(name = "raw_row_text", length = 400)
    private String rawRowText;

    @Column(name = "raw_description", nullable = false, length = 300)
    private String rawDescription;

    @Column(nullable = false, precision = 12, scale = 4)
    private BigDecimal quantity = BigDecimal.ONE;

    @Enumerated(EnumType.STRING)
    @Column(name = "sold_by", length = 20)
    private SoldBy soldBy;

    /** De la sub-línea de peso de Mercadona ("1,394 kg"). */
    @Column(name = "weight_value", precision = 12, scale = 4)
    private BigDecimal weightValue;

    @Column(name = "weight_unit", length = 10)
    private String weightUnit;

    /** Precio unitario tal como lo imprime el ticket, si lo imprime. Base de C2. */
    @Column(name = "printed_unit_price", precision = 12, scale = 4)
    private BigDecimal printedUnitPrice;

    @Column(name = "printed_unit_price_unit", length = 10)
    private String printedUnitPriceUnit;

    @Column(name = "line_total", nullable = false, precision = 12, scale = 4)
    private BigDecimal lineTotal;

    /** Solo Cash Fresh. Base de C3 y de la heurística H1. */
    @Column(name = "tax_letter", length = 1)
    private String taxLetter;

    @Column(name = "is_promo", nullable = false)
    private boolean promo;

    @Column(name = "promo_note", length = 200)
    private String promoNote;

    /** Nulo hasta que se confirma el emparejamiento en la pantalla de validación. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_product_id")
    private StoreProduct storeProduct;

    @Column(name = "match_confidence", precision = 5, scale = 4)
    private BigDecimal matchConfidence;

    @Enumerated(EnumType.STRING)
    @Column(name = "match_method", length = 20)
    private MatchMethod matchMethod;
}
