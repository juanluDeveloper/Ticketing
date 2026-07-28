package com.juanluidos.ticketing.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * Supermercado y las capacidades de su formato de ticket.
 *
 * <p>Las banderas {@code has*} no son metadatos decorativos: son lo que el motor
 * de validación consulta para decidir qué comprobaciones puede aplicar a un
 * ticket de este súper. Ver {@link CheckCode}.
 */
@Entity
@Table(name = "store")
@Getter
@Setter
public class Store {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 30)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    /** NIF/CIF de la cabecera. Permite auto-detectar el súper al extraer. */
    @Column(name = "tax_id", length = 20)
    private String taxId;

    /** Xinya usa punto; Mercadona y Cash Fresh, coma. */
    @Column(name = "decimal_separator", nullable = false, length = 1)
    private String decimalSeparator = ",";

    /** Habilita C3. Solo Cash Fresh imprime la letra por línea. */
    @Column(name = "has_line_tax_letter", nullable = false)
    private boolean hasLineTaxLetter;

    /** Habilita C4. Solo Xinya imprime "N ART.". */
    @Column(name = "has_article_count", nullable = false)
    private boolean hasArticleCount;

    /** Habilita C5. Los tres imprimen desglose de IVA. */
    @Column(name = "has_tax_breakdown", nullable = false)
    private boolean hasTaxBreakdown = true;

    /**
     * Si es cierto, el P.Unit solo se imprime cuando la cantidad es mayor que 1.
     * Dos consecuencias: C2 solo cubre parte de las líneas, y el entero inicial
     * de una descripción es cantidad únicamente si esa línea trae P.Unit
     * ("12 HUEVOS GRANDES-L" es una docena, no doce unidades).
     */
    @Column(name = "unit_price_only_when_multiple", nullable = false)
    private boolean unitPriceOnlyWhenMultiple;

    /** Mercadona añade una sub-línea "1,394 kg 3,05 €/kg" bajo el ítem a peso. */
    @Column(name = "has_weight_subline", nullable = false)
    private boolean hasWeightSubline;

    @Column(name = "date_format", length = 20)
    private String dateFormat;

    @Column(columnDefinition = "text")
    private String notes;

    @OneToMany(mappedBy = "store", fetch = FetchType.LAZY)
    private List<StoreTaxLetter> taxLetters = new ArrayList<>();
}
