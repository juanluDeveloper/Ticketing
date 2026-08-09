package com.juanluidos.ticketing.history;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Serie de precios de un producto en un súper, con sus métricas derivadas. */
public record PriceHistory(
        ProductInfo product,
        List<Point> points,
        Metrics metrics,
        /** Lecturas del cartel, en orden. Vacío en la inmensa mayoría de productos. */
        List<DeclaredEntry> declaredPrices,
        /**
         * Por qué este producto no tiene precio comparable, cuando no lo tiene.
         * Se dice explícitamente en vez de mostrar un hueco: un histórico vacío
         * porque falta teclear el tamaño del envase se arregla en diez segundos,
         * pero solo si alguien sabe que eso es lo que pasa.
         */
        String notComparableReason
) {

    public record ProductInfo(
            Long id,
            String storeCode,
            String storeName,
            String canonicalName,
            String displayName,
            String notes,
            BigDecimal packageSize,
            String packageUnit,
            String soldBy
    ) {
    }

    /**
     * Una lectura del cartel. No forma parte de la serie de tickets —no sale de
     * ninguna compra— pero es una serie por derecho propio: el precio del
     * mostrador también sube.
     */
    public record DeclaredEntry(
            Long id,
            LocalDate date,
            BigDecimal unitPrice,
            String unit,
            String note
    ) {
    }

    public record Point(
            LocalDate date,
            /** Lo que costó la pieza o el envase, siempre disponible. */
            BigDecimal pricePerPiece,
            /** €/kg, €/L o €/ud. Null si no es normalizable. */
            BigDecimal normalizedUnitPrice,
            String normalizedUnit,
            boolean promo,
            /** Falso para promociones y piezas de peso variable. */
            boolean countsForIncrease,
            Long ticketId
    ) {
    }

    public record Metrics(
            int purchaseCount,
            LocalDateTime firstSeenAt,
            LocalDateTime lastSeenAt,
            Integer daysSinceLast,
            BigDecimal lastNormalizedUnitPrice,
            String normalizedUnit,
            BigDecimal minNormalizedUnitPrice,
            BigDecimal maxNormalizedUnitPrice,
            /** Subidas reales de la serie limpia, con umbral para no contar redondeos. */
            int increaseCount,
            int decreaseCount,
            /** Coeficiente de variación de la serie limpia, en tanto por uno. */
            BigDecimal volatility,
            BigDecimal totalSpent,
            /** Cuántos puntos entran en el conteo de subidas… */
            int comparablePoints,
            /** …y cuántos quedan fuera, para que el número no engañe. */
            int excludedPoints
    ) {
    }
}
