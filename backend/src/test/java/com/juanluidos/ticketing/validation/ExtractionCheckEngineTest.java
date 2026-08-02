package com.juanluidos.ticketing.validation;

import com.juanluidos.ticketing.config.TicketingProperties;
import com.juanluidos.ticketing.domain.CheckCode;
import com.juanluidos.ticketing.domain.Store;
import com.juanluidos.ticketing.domain.StoreTaxLetter;
import com.juanluidos.ticketing.extraction.ExtractedTicket;
import com.juanluidos.ticketing.extraction.ExtractedTicket.ExtractedLineItem;
import com.juanluidos.ticketing.extraction.ExtractedTicket.ExtractedTaxBreakdown;
import com.juanluidos.ticketing.extraction.ExtractedTicket.ExtractedTotals;
import com.juanluidos.ticketing.extraction.ExtractedTicket.ExtractedWeight;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Datos reales de los tickets de Cash Fresh (29,48) y Mercadona (63,16).
 *
 * <p>Lo que más importa de esta clase no son los casos que pasan, sino
 * {@link #doesNotCatchAShiftOfTheWholeNumericRow()}: fija por escrito el punto
 * ciego del motor, para que nadie lo lea como que la revisión humana sobra.
 */
class ExtractionCheckEngineTest {

    private final ExtractionCheckEngine engine = new ExtractionCheckEngine(
            new TicketingProperties("x", null, null,
                    new TicketingProperties.Validation(new BigDecimal("0.01"), new BigDecimal("0.02")), null));

    // ------------------------------------------------------------------
    // Cash Fresh
    // ------------------------------------------------------------------

    @Test
    void theCorrectCashFreshReadingPassesEveryApplicableCheck() {
        CheckReport report = engine.evaluate(cashFreshTicket(correctCashFreshLines()),
                cashFresh(), cashFreshLetters());

        assertThat(outcome(report, CheckCode.C1).passed()).isTrue();
        assertThat(outcome(report, CheckCode.C2).passed()).isTrue();
        assertThat(outcome(report, CheckCode.C3).passed()).isTrue();
        assertThat(outcome(report, CheckCode.C5).passed()).isTrue();
        assertThat(report.hasErrors()).isFalse();
        // Todas las líneas traen precio unitario y letra: cobertura total.
        assertThat(report.coverageRatio()).isEqualByComparingTo(BigDecimal.ONE);
    }

    /** Cash Fresh no imprime recuento de artículos: C4 no puede correr. */
    @Test
    void c4IsNotApplicableToCashFresh() {
        CheckReport report = engine.evaluate(cashFreshTicket(correctCashFreshLines()),
                cashFresh(), cashFreshLetters());

        CheckReport.CheckOutcome c4 = outcome(report, CheckCode.C4);
        assertThat(c4.applicable()).isFalse();
        assertThat(c4.passed()).isNull();
    }

    /**
     * Si la letra se lee de una fila y el importe de otra, las bases por letra
     * dejan de cuadrar con las impresas. Aquí se intercambian las letras de la
     * leche (A, 4 %) y del quitapelusas (C, 21 %).
     */
    @Test
    void c3CatchesALetterAttachedToTheWrongAmount() {
        List<ExtractedLineItem> lines = new ArrayList<>(correctCashFreshLines());
        lines.set(8, withLetter(lines.get(8), "A"));   // QUITAPELUSAS, era C
        lines.set(9, withLetter(lines.get(9), "C"));   // LECHE ENTERA, era A

        CheckReport report = engine.evaluate(cashFreshTicket(lines), cashFresh(), cashFreshLetters());

        assertThat(outcome(report, CheckCode.C3).passed()).isFalse();
        // C1 sigue en verde: la suma de importes no ha cambiado.
        assertThat(outcome(report, CheckCode.C1).passed()).isTrue();
        assertThat(report.findings())
                .anyMatch(f -> f.code() == CheckCode.C3 && f.message().contains("desplazamiento"));
    }

    /**
     * EL PUNTO CIEGO. Si la extracción desplaza las descripciones respecto al
     * bloque numérico entero — importe Y letra juntos, que es como van impresos
     * en la misma fila física — no falla ninguna comprobación: la suma es la
     * misma, cada fila sigue cumpliendo cantidad x precio, y las bases por letra
     * siguen cuadrando.
     *
     * <p>Que este ticket concreto se resolviera fue porque una persona vio que
     * "leche al 21 %" y "detergente al 4 %" son imposibles. Eso el motor no lo
     * sabe hacer sin histórico del producto (heurística H1, aún sin implementar).
     */
    @Test
    void doesNotCatchAShiftOfTheWholeNumericRow() {
        List<ExtractedLineItem> correct = correctCashFreshLines();
        List<ExtractedLineItem> shifted = new ArrayList<>();
        for (int i = 0; i < correct.size(); i++) {
            // Cada descripción se queda con los números de la fila siguiente.
            ExtractedLineItem numbers = correct.get((i + 1) % correct.size());
            shifted.add(withDescription(numbers, correct.get(i).rawDescription()));
        }

        CheckReport report = engine.evaluate(cashFreshTicket(shifted), cashFresh(), cashFreshLetters());

        assertThat(outcome(report, CheckCode.C1).passed()).isTrue();
        assertThat(outcome(report, CheckCode.C2).passed()).isTrue();
        assertThat(outcome(report, CheckCode.C3).passed()).isTrue();
        assertThat(outcome(report, CheckCode.C5).passed()).isTrue();
        assertThat(report.hasErrors()).isFalse();
    }

    /** Con una sola letra en todo el ticket, C3 no distingue nada y lo dice. */
    @Test
    void c3IsNotApplicableWhenEveryLineSharesOneLetter() {
        List<ExtractedLineItem> lines = correctCashFreshLines().stream()
                .map(l -> withLetter(l, "C"))
                .toList();

        CheckReport.CheckOutcome c3 = outcome(
                engine.evaluate(cashFreshTicket(lines), cashFresh(), cashFreshLetters()), CheckCode.C3);

        assertThat(c3.applicable()).isFalse();
        assertThat(c3.detail()).contains("no desambigua");
    }

    // ------------------------------------------------------------------
    // Mercadona
    // ------------------------------------------------------------------

    @Test
    void theCorrectMercadonaReadingPassesAndReportsItsLowCoverage() {
        CheckReport report = engine.evaluate(mercadonaTicket(correctMercadonaLines()),
                mercadona(), List.of());

        assertThat(outcome(report, CheckCode.C1).passed()).isTrue();
        assertThat(outcome(report, CheckCode.C2).passed()).isTrue();
        assertThat(outcome(report, CheckCode.C3).applicable()).isFalse();
        assertThat(outcome(report, CheckCode.C4).applicable()).isFalse();

        // 26 líneas y solo 6 comprobables: las 5 de cantidad mayor que 1 más el
        // mango a peso. Las otras 20 no las mira nadie salvo una persona.
        assertThat(report.lineCount()).isEqualTo(26);
        assertThat(report.coveredLines()).isEqualTo(6);
        assertThat(report.coverageRatio()).isEqualByComparingTo(new BigDecimal("0.2308"));
    }

    /**
     * El caso que sí pilla C2: el importe viene de otra fila pero la cantidad y
     * el precio unitario se quedan, así que la multiplicación deja de cuadrar.
     */
    @Test
    void c2CatchesAnAmountTakenFromAnotherRow() {
        List<ExtractedLineItem> lines = new ArrayList<>(correctMercadonaLines());
        // BEBIDA AVELLANAS: 3 x 1,25 son 3,75, no 2,30 (que es de la leche).
        lines.set(4, new ExtractedLineItem("3 BEBIDA AVELLANAS", "BEBIDA AVELLANAS",
                new BigDecimal("3"), "unit", null, new BigDecimal("1.25"), "ud",
                new BigDecimal("2.30"), null, false, null));

        CheckReport report = engine.evaluate(mercadonaTicket(lines), mercadona(), List.of());

        assertThat(outcome(report, CheckCode.C2).passed()).isFalse();
        assertThat(report.findings())
                .anyMatch(f -> f.code() == CheckCode.C2
                        && f.message().contains("BEBIDA AVELLANAS")
                        && f.message().contains("viene de otra fila"));
    }

    /** El mango a peso redondea (1,394 x 3,05 = 4,2517): la tolerancia lo absorbe. */
    @Test
    void weightLinesTolerateRounding() {
        CheckReport report = engine.evaluate(mercadonaTicket(correctMercadonaLines()),
                mercadona(), List.of());

        assertThat(report.findings()).noneMatch(f -> f.code() == CheckCode.C2);
    }

    // ------------------------------------------------------------------
    // Datos
    // ------------------------------------------------------------------

    private List<ExtractedLineItem> correctCashFreshLines() {
        return List.of(
                cf("ARENA GATOS SEPIOLIT", 1, "1.45", "1.45", "C"),
                cf("CHOCOLATE BLANCO IFA", 1, "2.18", "2.18", "B"),
                cf("ESPINACAS C/GAR. 360", 1, "2.98", "2.98", "B"),
                cf("FLOTA LAVAV. 1,10L", 1, "1.25", "1.25", "C"),
                cf("IFA SABE LEJIA C/DET", 1, "1.12", "1.12", "C"),
                cf("JAMON RESERVA +14 ME", 2, "2.79", "5.58", "B"),
                cf("PAPILLA FRUTAS VARIA", 1, "2.19", "2.19", "B"),
                cf("QUESO RULO 180GR", 1, "2.55", "2.55", "A"),
                cf("QUITAPELUSAS IFA SAB", 2, "1.75", "3.50", "C"),
                cf("LECHE ENTERA ACORES", 1, "0.79", "0.79", "A"),
                cf("DET MARSE FLOTA 100D", 1, "5.89", "5.89", "C"));
    }

    private ExtractedTicket cashFreshTicket(List<ExtractedLineItem> lines) {
        return new ExtractedTicket(
                new ExtractedTicket.ExtractedStore("Cash Fresh", "B41544503", null),
                "2026-07-15T21:54:08", "260715/219/103/0168", "EUR", ",", null, lines,
                new ExtractedTotals(new BigDecimal("29.48"), List.of(
                        new ExtractedTaxBreakdown(new BigDecimal("0.04"), new BigDecimal("3.21"), new BigDecimal("0.13")),
                        new ExtractedTaxBreakdown(new BigDecimal("0.10"), new BigDecimal("11.76"), new BigDecimal("1.17")),
                        new ExtractedTaxBreakdown(new BigDecimal("0.21"), new BigDecimal("10.92"), new BigDecimal("2.29")))));
    }

    private List<ExtractedLineItem> correctMercadonaLines() {
        List<ExtractedLineItem> lines = new ArrayList<>();
        lines.add(m("CUBO FREGAR C/RUEDAS", "3.70"));
        lines.add(m("FRUTOS ROJOS", "1.00"));
        lines.add(m("CARACOLA PASAS 10%", "1.00"));
        lines.add(m("SAL LAVAVAJILLAS", "0.95"));
        lines.add(mq("BEBIDA AVELLANAS", 3, "1.25", "3.75"));
        lines.add(mq("LECHE FRESCA ENT", 2, "1.15", "2.30"));
        lines.add(m("IMPULSOR ROYAL 80GR", "2.25"));
        lines.add(m("BUÑUELO DE BACALAO", "3.85"));
        lines.add(m("SOJA NATURAL", "1.20"));
        lines.add(m("COPO INTEGRAL CHOCO", "2.30"));
        lines.add(m("MANGO S/AZ AÑADIDO", "1.25"));
        lines.add(m("JENGIBRE", "1.87"));
        // El "12" es parte del nombre: una docena a 3,20, no doce unidades.
        lines.add(m("12 HUEVOS GRANDES-L", "3.20"));
        lines.add(mq("HUMMUS PIMIENTO", 2, "1.45", "2.90"));
        lines.add(m("HARINA REPOSTERIA", "1.00"));
        lines.add(mq("BOQUERONES ALIÑADOS", 2, "1.65", "3.30"));
        lines.add(m("ARROZ DE VERDURAS", "3.50"));
        lines.add(m("AZUCAR VAINILLADO", "1.30"));
        lines.add(m("AGUACATE BANDEJA", "3.27"));
        // Misma descripción, tres importes: piezas de peso variable.
        lines.add(mPiece("TUBO DE POTA", "1.82"));
        lines.add(mPiece("TUBO DE POTA", "2.30"));
        lines.add(mPiece("TUBO DE POTA", "1.95"));
        lines.add(m("PIQUITOS SALVADO", "0.90"));
        lines.add(m("ATÚN", "4.45"));
        lines.add(mq("ALMEJA PACIFIC", 2, "1.80", "3.60"));
        lines.add(new ExtractedLineItem("1 MANGO  1,394 kg  3,05 €/kg", "MANGO",
                BigDecimal.ONE, "weight", new ExtractedWeight(new BigDecimal("1.394"), "kg"),
                new BigDecimal("3.05"), "kg", new BigDecimal("4.25"), null, false, null));
        return lines;
    }

    private ExtractedTicket mercadonaTicket(List<ExtractedLineItem> lines) {
        return new ExtractedTicket(
                new ExtractedTicket.ExtractedStore("Mercadona", "A-46103834", null),
                "2026-06-24T21:51:00", "2276-012-556655", "EUR", ",", null, lines,
                new ExtractedTotals(new BigDecimal("63.16"), List.of(
                        new ExtractedTaxBreakdown(new BigDecimal("0.04"), new BigDecimal("13.38"), new BigDecimal("0.54")),
                        new ExtractedTaxBreakdown(new BigDecimal("0.10"), new BigDecimal("39.40"), new BigDecimal("3.94")),
                        new ExtractedTaxBreakdown(new BigDecimal("0.21"), new BigDecimal("4.88"), new BigDecimal("1.02")))));
    }

    private ExtractedLineItem cf(String desc, int qty, String unit, String total, String letter) {
        return new ExtractedLineItem(qty + "x " + unit + "  " + total + " " + letter, desc,
                BigDecimal.valueOf(qty), "unit", null, new BigDecimal(unit), "ud",
                new BigDecimal(total), letter, false, null);
    }

    /** Línea de Mercadona a cantidad 1: sin precio unitario impreso, sin cobertura. */
    private ExtractedLineItem m(String desc, String total) {
        return new ExtractedLineItem("1 " + desc + "  " + total, desc,
                BigDecimal.ONE, "unit", null, null, null, new BigDecimal(total), null, false, null);
    }

    private ExtractedLineItem mq(String desc, int qty, String unit, String total) {
        return new ExtractedLineItem(qty + " " + desc + "  " + unit + "  " + total, desc,
                BigDecimal.valueOf(qty), "unit", null, new BigDecimal(unit), "ud",
                new BigDecimal(total), null, false, null);
    }

    private ExtractedLineItem mPiece(String desc, String total) {
        return new ExtractedLineItem("1 " + desc + "  " + total, desc,
                BigDecimal.ONE, "piece_variable", null, null, null,
                new BigDecimal(total), null, false, null);
    }

    private ExtractedLineItem withLetter(ExtractedLineItem l, String letter) {
        return new ExtractedLineItem(l.rawRowText(), l.rawDescription(), l.quantity(), l.soldBy(),
                l.weight(), l.unitPrice(), l.unitPriceUnit(), l.lineTotal(), letter,
                l.isPromo(), l.promoNote());
    }

    private ExtractedLineItem withDescription(ExtractedLineItem l, String description) {
        return new ExtractedLineItem(l.rawRowText(), description, l.quantity(), l.soldBy(),
                l.weight(), l.unitPrice(), l.unitPriceUnit(), l.lineTotal(), l.taxLetter(),
                l.isPromo(), l.promoNote());
    }

    private Store mercadona() {
        Store s = new Store();
        s.setName("Mercadona");
        s.setUnitPriceOnlyWhenMultiple(true);
        s.setHasWeightSubline(true);
        return s;
    }

    private Store cashFresh() {
        Store s = new Store();
        s.setName("Cash Fresh");
        s.setHasLineTaxLetter(true);
        return s;
    }

    private List<StoreTaxLetter> cashFreshLetters() {
        return List.of(letter("A", "0.0400"), letter("B", "0.1000"), letter("C", "0.2100"));
    }

    private StoreTaxLetter letter(String letter, String rate) {
        StoreTaxLetter l = new StoreTaxLetter();
        l.setLetter(letter);
        l.setRate(new BigDecimal(rate));
        return l;
    }

    private CheckReport.CheckOutcome outcome(CheckReport report, CheckCode code) {
        return report.outcomes().stream()
                .filter(o -> o.code() == code)
                .findFirst()
                .orElseThrow(() -> new AssertionError("sin resultado para " + code));
    }
}
