package com.juanluidos.ticketing.validation;

import com.juanluidos.ticketing.config.TicketingProperties;
import com.juanluidos.ticketing.domain.CheckCode;
import com.juanluidos.ticketing.domain.IssueSeverity;
import com.juanluidos.ticketing.domain.Store;
import com.juanluidos.ticketing.extraction.ExtractedTicket;
import com.juanluidos.ticketing.extraction.ExtractedTicket.ExtractedLineItem;
import com.juanluidos.ticketing.extraction.ExtractedTicket.ExtractedTotals;
import com.juanluidos.ticketing.extraction.ExtractedTicket.ExtractedWeight;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Las líneas a peso llevan el precio en €/kg, así que multiplicarlo por la
 * cantidad no significa nada. Antes se hacía igual y C2 inventaba errores en
 * líneas correctas.
 */
class WeightLineCheckTest {

    private final ExtractionCheckEngine engine = new ExtractionCheckEngine(
            new TicketingProperties("x", null, null,
                    new TicketingProperties.Validation(new BigDecimal("0.01"), new BigDecimal("0.02")),
                    null));

    /** El caso real: 1,082 kg a 3,05 €/kg son 3,30. La línea está bien. */
    @Test
    void acceptsAWeightLineWhoseWeightTimesPricePerKiloMatches() {
        CheckReport report = evaluate(weightLine("1.082", "3.05", "3.30"));

        assertThat(outcome(report, CheckCode.C2).passed()).isTrue();
        assertThat(report.findings()).isEmpty();
    }

    /**
     * El fallo que motivó esto: el modelo marca "a peso" pero se deja el peso.
     * Antes se caía a la cantidad y salía "1 x 3,05 no da 3,30" en una línea que
     * en el papel cuadra perfectamente.
     */
    @Test
    void doesNotInventAnErrorWhenTheWeightIsMissing() {
        CheckReport report = evaluate(weightLineWithoutWeight("3.05", "3.30"));

        var finding = report.findings().stream()
                .filter(f -> f.code() == CheckCode.C2)
                .findFirst()
                .orElseThrow();

        // Aviso accionable, no error: el total del ticket sigue cuadrando y lo
        // único que falta es el dato para poder comparar por kilo.
        assertThat(finding.severity()).isEqualTo(IssueSeverity.WARN);
        assertThat(finding.message()).contains("no se ha leído el peso");
        assertThat(finding.message()).doesNotContain("no da");
        assertThat(report.hasErrors()).isFalse();
    }

    /** Con el peso puesto, un importe que no cuadra sí es un error de verdad. */
    @Test
    void stillCatchesAWeightLineThatDoesNotAddUp() {
        CheckReport report = evaluate(weightLine("1.082", "3.05", "9.99"));

        assertThat(outcome(report, CheckCode.C2).passed()).isFalse();
        assertThat(report.hasErrors()).isTrue();
    }

    /** El redondeo de la báscula: 1,394 x 3,05 = 4,2517, impreso 4,25. */
    @Test
    void toleratesTheRoundingOfTheScale() {
        CheckReport report = evaluate(weightLine("1.394", "3.05", "4.25"));

        assertThat(outcome(report, CheckCode.C2).passed()).isTrue();
    }

    // ------------------------------------------------------------------

    private CheckReport evaluate(ExtractedLineItem line) {
        Store store = new Store();
        store.setName("Mercadona");
        store.setHasWeightSubline(true);

        return engine.evaluate(new ExtractedTicket(
                new ExtractedTicket.ExtractedStore("Mercadona", "A-46103834", null),
                "2026-06-24T21:51:00", "1", "EUR", ",", null, List.of(line),
                new ExtractedTotals(line.lineTotal(), List.of())), store, List.of());
    }

    private ExtractedLineItem weightLine(String weight, String pricePerKilo, String total) {
        return new ExtractedLineItem(
                "1 MANGO   " + weight + " kg  " + pricePerKilo + " EUR/kg  " + total,
                "MANGO", BigDecimal.ONE, "weight",
                new ExtractedWeight(new BigDecimal(weight), "kg"),
                new BigDecimal(pricePerKilo), "kg", new BigDecimal(total), null, false, null);
    }

    private ExtractedLineItem weightLineWithoutWeight(String pricePerKilo, String total) {
        return new ExtractedLineItem(
                "1 MANGO   1,082 kg  " + pricePerKilo + " EUR/kg  " + total,
                "MANGO", BigDecimal.ONE, "weight", null,
                new BigDecimal(pricePerKilo), "kg", new BigDecimal(total), null, false, null);
    }

    private CheckReport.CheckOutcome outcome(CheckReport report, CheckCode code) {
        return report.outcomes().stream()
                .filter(o -> o.code() == code)
                .findFirst()
                .orElseThrow(() -> new AssertionError("sin resultado para " + code));
    }
}
