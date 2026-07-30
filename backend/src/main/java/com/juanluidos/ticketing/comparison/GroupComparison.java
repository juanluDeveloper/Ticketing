package com.juanluidos.ticketing.comparison;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Comparación de un grupo entre súper.
 *
 * <p>Las entradas sin precio comparable NO se mezclan con el ranking ni se
 * omiten: van en su propia lista, cada una con el motivo. Un súper donde nunca
 * he comprado el producto y un súper donde es más caro son cosas distintas, y
 * dejarlas juntas haría creer que el ranking está completo.
 */
public record GroupComparison(
        GroupInfo group,
        /** Ordenado por precio normalizado real, de más barato a más caro. */
        List<Entry> ranking,
        List<Entry> notComparable,
        Verdict verdict,
        /** Aviso sobre la calidad del dato, no sobre el precio. */
        String dataWarning
) {

    public record GroupInfo(
            Long id,
            String name,
            String comparisonDimension,
            String comparisonUnit,
            Long categoryId,
            String categoryName,
            int memberCount
    ) {
    }

    public record Entry(
            Long storeProductId,
            String storeCode,
            String storeName,
            String productName,
            BigDecimal normalizedUnitPrice,
            String unit,
            /** Fecha del precio, no de hoy: esto no es un precio en vivo. */
            LocalDate observedAt,
            Integer ageDays,
            boolean promo,
            boolean preferred,
            /** Precio menos la prima. Solo se calcula para el preferido. */
            BigDecimal adjustedPrice,
            String notComparableReason
    ) {
    }

    /**
     * Siempre las dos cosas: el más barato objetivo y la elección ajustada por
     * preferencia, con lo que cuesta la diferencia. Nunca solo la recomendación,
     * porque entonces la prima se vuelve invisible.
     */
    public record Verdict(
            Entry cheapest,
            Entry chosen,
            boolean preferenceApplied,
            boolean preferenceWins,
            /** €/unidad de más que cuesta seguir la preferencia. */
            BigDecimal preferenceCost,
            /** Lo mismo en porcentaje sobre el más barato. */
            BigDecimal preferenceCostPct,
            String explanation
    ) {
    }
}
