package com.juanluidos.ticketing.validation;

import com.juanluidos.ticketing.config.TicketingProperties;
import com.juanluidos.ticketing.domain.CheckCode;
import com.juanluidos.ticketing.domain.Store;
import com.juanluidos.ticketing.extraction.ExtractedTicket;
import com.juanluidos.ticketing.extraction.ExtractedTicket.ExtractedLineItem;
import com.juanluidos.ticketing.extraction.ExtractedTicket.ExtractedTotals;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Las dos heurísticas semánticas. Existen porque ninguna comprobación
 * aritmética mira ni {@code sold_by} ni si la transcripción de la fila llegó
 * completa, y las dos cosas se degradan en silencio.
 */
class SoldByAndRowTextChecksTest {

    private final ExtractionCheckEngine engine = new ExtractionCheckEngine(
            new TicketingProperties("x", null, null,
                    new TicketingProperties.Validation(new BigDecimal("0.01"), new BigDecimal("0.02"))));

    /**
     * El fallo real observado: el modelo clasificó las 26 líneas de un Mercadona
     * como pieza de peso variable, lo que habría dejado el ticket entero fuera
     * del precio normalizado sin que nada chillara.
     */
    @Test
    void h3FlagsAWholeTicketClassifiedAsVariablePiece() {
        CheckReport report = evaluate(List.of(
                line("CUBO FREGAR", "3.70", "piece_variable", "1 CUBO FREGAR 3,70"),
                line("FRUTOS ROJOS", "1.00", "piece_variable", "1 FRUTOS ROJOS 1,00"),
                line("SAL LAVAVAJILLAS", "0.95", "piece_variable", "1 SAL LAVAVAJILLAS 0,95")));

        assertThat(outcome(report, CheckCode.H3).passed()).isFalse();
        assertThat(report.findings())
                .anyMatch(f -> f.code() == CheckCode.H3 && f.message().contains("piece_variable"));
    }

    /**
     * Un ticket entero envasado es lo normal — el de Cash Fresh lo es y está
     * bien — así que marcarlo daría un falso positivo en casi todos los tickets.
     */
    @Test
    void h3AcceptsAWholeTicketClassifiedAsUnit() {
        CheckReport report = evaluate(List.of(
                line("CUBO FREGAR", "3.70", "unit", "1 CUBO FREGAR 3,70"),
                line("FRUTOS ROJOS", "1.00", "unit", "1 FRUTOS ROJOS 1,00"),
                line("SAL LAVAVAJILLAS", "0.95", "unit", "1 SAL LAVAVAJILLAS 0,95")));

        assertThat(outcome(report, CheckCode.H3).passed()).isTrue();
    }

    /** Con pocas líneas la señal no significa nada y no se emite. */
    @Test
    void h3StaysQuietOnVeryShortTickets() {
        CheckReport report = evaluate(List.of(
                line("TUBO DE POTA", "1.82", "piece_variable", "1 TUBO DE POTA 1,82"),
                line("TUBO DE POTA", "2.30", "piece_variable", "1 TUBO DE POTA 2,30")));

        assertThat(outcome(report, CheckCode.H3).applicable()).isFalse();
    }

    /**
     * Si la fila transcrita no lleva el importe, se leyó por separado de la
     * descripción y la defensa contra el desplazamiento no está actuando, por
     * mucho que el resto de comprobaciones pasen.
     */
    @Test
    void h4FlagsRowsTranscribedWithoutTheirAmount() {
        CheckReport report = evaluate(List.of(
                line("CUBO FREGAR", "3.70", "unit", "1 CUBO FREGAR C/RUEDAS"),
                line("FRUTOS ROJOS", "1.00", "unit", "1 FRUTOS ROJOS 1,00"),
                line("SAL LAVAVAJILLAS", "0.95", "unit", "1 SAL LAVAVAJILLAS 0,95")));

        assertThat(outcome(report, CheckCode.H4).passed()).isFalse();
        assertThat(report.findings())
                .anyMatch(f -> f.code() == CheckCode.H4 && f.message().contains("1 de 3"));
    }

    /** El importe puede venir con coma o con punto según el súper. */
    @Test
    void h4AcceptsEitherDecimalSeparatorInTheTranscribedRow() {
        CheckReport report = evaluate(List.of(
                line("BEBIDA", "1.65", "unit", "BEBIDA 3 1.65 1.65"),
                line("FRUTOS ROJOS", "1.00", "unit", "1 FRUTOS ROJOS 1,00"),
                line("SAL LAVAVAJILLAS", "0.95", "unit", "1 SAL LAVAVAJILLAS 0,95")));

        assertThat(outcome(report, CheckCode.H4).passed()).isTrue();
    }

    private CheckReport evaluate(List<ExtractedLineItem> lines) {
        BigDecimal total = lines.stream()
                .map(ExtractedLineItem::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Store store = new Store();
        store.setName("Mercadona");
        return engine.evaluate(new ExtractedTicket(
                new ExtractedTicket.ExtractedStore("Mercadona", "A-46103834", null),
                "2026-06-24T21:51:00", "1", "EUR", ",", null, lines,
                new ExtractedTotals(total, List.of())), store, List.of());
    }

    private ExtractedLineItem line(String desc, String total, String soldBy, String rowText) {
        return new ExtractedLineItem(rowText, desc, BigDecimal.ONE, soldBy, null,
                new BigDecimal(total), "ud", new BigDecimal(total), null, false, null);
    }

    private CheckReport.CheckOutcome outcome(CheckReport report, CheckCode code) {
        return report.outcomes().stream()
                .filter(o -> o.code() == code)
                .findFirst()
                .orElseThrow(() -> new AssertionError("sin resultado para " + code));
    }
}
