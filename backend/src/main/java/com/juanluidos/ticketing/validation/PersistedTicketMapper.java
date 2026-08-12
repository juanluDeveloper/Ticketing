package com.juanluidos.ticketing.validation;

import com.juanluidos.ticketing.domain.LineItem;
import com.juanluidos.ticketing.domain.SoldBy;
import com.juanluidos.ticketing.domain.Ticket;
import com.juanluidos.ticketing.domain.TicketGeneralDiscount;
import com.juanluidos.ticketing.domain.TicketTaxSummary;
import com.juanluidos.ticketing.extraction.ExtractedTicket;

import java.util.List;

/**
 * Reconstruye la forma del Anexo B a partir de lo ya guardado, para poder volver
 * a pasar las comprobaciones después de que la persona corrija.
 *
 * <p>Sin esto los semáforos se quedarían congelados en el resultado de la
 * extracción, que es justo lo contrario de lo que necesita una pantalla donde se
 * corrige: al arreglar la línea que fallaba, C1 y C2 tienen que ponerse verdes.
 */
public final class PersistedTicketMapper {

    private PersistedTicketMapper() {
    }

    public static ExtractedTicket toExtracted(Ticket ticket, List<LineItem> lines,
                                              List<TicketTaxSummary> taxes,
                                              List<TicketGeneralDiscount> discounts) {
        List<ExtractedTicket.ExtractedLineItem> mapped = lines.stream()
                .map(PersistedTicketMapper::toLine)
                .toList();

        List<ExtractedTicket.ExtractedTaxBreakdown> breakdown = taxes.stream()
                .map(t -> new ExtractedTicket.ExtractedTaxBreakdown(
                        t.getRate(), t.getBaseAmount(), t.getTaxAmount()))
                .toList();

        List<ExtractedTicket.ExtractedGeneralDiscount> mappedDiscounts = discounts.stream()
                .map(d -> new ExtractedTicket.ExtractedGeneralDiscount(
                        d.getDescription(), d.getAmount()))
                .toList();

        return new ExtractedTicket(
                new ExtractedTicket.ExtractedStore(
                        ticket.getStore() == null ? null : ticket.getStore().getName(),
                        ticket.getStore() == null ? null : ticket.getStore().getTaxId(),
                        null),
                ticket.getPurchasedAt() == null ? null : ticket.getPurchasedAt().toString(),
                ticket.getReceiptNumber(),
                ticket.getCurrency(),
                ticket.getStore() == null ? "," : ticket.getStore().getDecimalSeparator(),
                ticket.getArticleCount(),
                mapped,
                new ExtractedTicket.ExtractedTotals(
                        ticket.getTotal(), mappedDiscounts, ticket.getAmountPaid(), breakdown));
    }

    private static ExtractedTicket.ExtractedLineItem toLine(LineItem line) {
        ExtractedTicket.ExtractedWeight weight = line.getWeightValue() == null
                ? null
                : new ExtractedTicket.ExtractedWeight(line.getWeightValue(), line.getWeightUnit());

        return new ExtractedTicket.ExtractedLineItem(
                line.getRawRowText(),
                line.getRawDescription(),
                line.getQuantity(),
                toSoldByCode(line.getSoldBy()),
                weight,
                line.getPrintedUnitPrice(),
                line.getPrintedUnitPriceUnit(),
                line.getLineTotal(),
                line.getTaxLetter(),
                line.isPromo(),
                line.getPromoNote());
    }

    private static String toSoldByCode(SoldBy soldBy) {
        if (soldBy == null) {
            return null;
        }
        return switch (soldBy) {
            case WEIGHT -> "weight";
            case VARIABLE_PIECE -> "piece_variable";
            case PACKAGE -> "unit";
        };
    }
}
