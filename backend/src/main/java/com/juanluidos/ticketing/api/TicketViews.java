package com.juanluidos.ticketing.api;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** Lo que ve la pantalla de validación. */
public final class TicketViews {

    private TicketViews() {
    }

    public record TicketSummary(
            Long id,
            String status,
            String storeCode,
            String storeName,
            LocalDateTime purchasedAt,
            String receiptNumber,
            /** Total bruto de los artículos, antes de descuentos generales. */
            BigDecimal total,
            BigDecimal generalDiscountTotal,
            /** Importe final realmente cobrado. */
            BigDecimal amountPaid,
            Integer articleCount,
            int lineCount,
            /** Fracción de líneas con alguna comprobación no trivial detrás. */
            BigDecimal coverageRatio,
            LocalDateTime createdAt,
            String extractionError
    ) {
    }

    public record TicketDetail(
            TicketSummary summary,
            List<LineView> lines,
            List<CheckView> checks,
            List<IssueView> ticketIssues,
            List<TaxView> taxes,
            List<GeneralDiscountView> generalDiscounts,
            /** Aviso que la UI enseña arriba cuando las checks apenas cubren nada. */
            String coverageWarning
    ) {
    }

    public record LineView(
            Long id,
            Integer lineNo,
            /** La fila física transcrita, para comparar con la foto sin bizquear. */
            String rawRowText,
            String rawDescription,
            BigDecimal quantity,
            BigDecimal printedUnitPrice,
            BigDecimal lineTotal,
            String taxLetter,
            String soldBy,
            boolean promo,
            BigDecimal weightValue,
            String weightUnit,
            ProductView product,
            String matchMethod,
            BigDecimal matchConfidence,
            /** Tamaño deducido de la descripción, para prerrellenar al crear producto. */
            SizeSuggestion sizeSuggestion,
            List<IssueView> issues
    ) {
    }

    public record ProductView(
            Long id,
            String canonicalName,
            String displayName,
            String notes,
            BigDecimal packageSize,
            String packageUnit,
            String soldBy,
            Long categoryId
    ) {
    }

    /**
     * {@code applicable} y {@code passed} van separados: si el formato del súper
     * no imprime lo que la comprobación necesita, la UI pinta gris, nunca verde.
     */
    public record CheckView(
            String code,
            String description,
            boolean applicable,
            Boolean passed,
            Integer linesCovered,
            String detail
    ) {
    }

    public record IssueView(
            Long id,
            String code,
            String severity,
            String message,
            BigDecimal expected,
            BigDecimal actual,
            Long lineItemId
    ) {
    }

    public record TaxView(BigDecimal rate, BigDecimal base, BigDecimal tax, String letter) {
    }

    public record GeneralDiscountView(Long id, Integer position, String description, BigDecimal amount) {
    }

    public record SizeSuggestion(BigDecimal value, String unit, String dimension) {
    }

    public record UploadResponse(Long ticketId, String status, List<Long> sameImageAs) {
    }

    public record StoreView(Long id, String code, String name, String taxId) {
    }

    public record CategoryView(Long id, String code, String name, Long parentId) {
    }
}
