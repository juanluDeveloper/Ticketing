package com.juanluidos.ticketing.extraction;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.math.BigDecimal;
import java.util.List;

/**
 * Salida cruda de la extracción, con la forma del Anexo B.
 *
 * <p>Las claves van en snake_case sin acentos y todas son obligatorias en el
 * esquema: sin fijarlas, el modelo las devuelve con tildes y espacios
 * ("precio unitario") y cada ticket sale con una forma distinta.
 *
 * <p>Falta a propósito el {@code checksum_ok} del Anexo B: que el modelo se
 * autoevalúe el checksum no vale de nada, porque si suma mal los importes
 * también se dará por bueno. Lo recalcula el backend a partir de estos números.
 *
 * <p>Esto es la verdad de lo que el ticket dice, no el mapeo a productos: el
 * matching ocurre después, en la validación.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ExtractedTicket(
        ExtractedStore store,
        /** ISO-8601 sin zona; hora local del súper. */
        String purchasedAt,
        String receiptNumber,
        String currency,
        String decimalSeparator,
        /** Solo si el ticket lo imprime. Alimenta C4. */
        Integer articleCount,
        List<ExtractedLineItem> lineItems,
        ExtractedTotals totals
) {

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ExtractedStore(String name, String nif, String address) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ExtractedLineItem(
            /**
             * La fila física transcrita literal. Los demás campos se leen de
             * esta cadena, así que descripción e importe salen de la misma fila
             * por construcción: pedir los campos sueltos es lo que deja que un
             * precio se enganche a la descripción de al lado.
             */
            String rawRowText,
            String rawDescription,
            BigDecimal quantity,
            /** "unit" | "weight" | "piece_variable" */
            String soldBy,
            ExtractedWeight weight,
            BigDecimal unitPrice,
            String unitPriceUnit,
            BigDecimal lineTotal,
            /** "A" | "B" | "C", solo Cash Fresh. */
            String taxLetter,
            Boolean isPromo,
            String promoNote
    ) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ExtractedWeight(BigDecimal value, String unit) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ExtractedTotals(
            /** Total bruto de los artículos, antes de descuentos generales. */
            BigDecimal total,
            List<ExtractedGeneralDiscount> generalDiscounts,
            /** Importe final cobrado después de los descuentos generales. */
            BigDecimal amountPaid,
            List<ExtractedTaxBreakdown> taxBreakdown
    ) {
        /** Compatibilidad para los tickets sin descuentos usados en tests y datos anteriores. */
        public ExtractedTotals(BigDecimal total, List<ExtractedTaxBreakdown> taxBreakdown) {
            this(total, List.of(), total, taxBreakdown);
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ExtractedGeneralDiscount(String description, BigDecimal amount) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ExtractedTaxBreakdown(BigDecimal rate, BigDecimal base, BigDecimal tax) {
    }
}
