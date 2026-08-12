package com.juanluidos.ticketing.validation;

import com.juanluidos.ticketing.config.TicketingProperties;
import com.juanluidos.ticketing.domain.CheckCode;
import com.juanluidos.ticketing.domain.IssueSeverity;
import com.juanluidos.ticketing.domain.Store;
import com.juanluidos.ticketing.domain.StoreTaxLetter;
import com.juanluidos.ticketing.extraction.ExtractedTicket;
import com.juanluidos.ticketing.extraction.ExtractedTicket.ExtractedLineItem;
import com.juanluidos.ticketing.extraction.ExtractedTicket.ExtractedTaxBreakdown;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Las comprobaciones aritméticas y heurísticas sobre una extracción.
 *
 * <p>Cada una necesita cierta redundancia impresa en el ticket, y esa redundancia
 * depende del formato del súper. El motor consulta las banderas de {@link Store}
 * y marca como <em>no aplicable</em> lo que no puede correr, en vez de darlo por
 * bueno.
 *
 * <p>Ninguna sustituye la revisión humana. C1 en particular no detecta el fallo
 * más traicionero — que una descripción se empareje con el importe de la fila de
 * al lado — porque la suma no cambia al desplazar. Eso lo pillan C2 y C3, y solo
 * en las líneas donde el ticket imprime lo que hace falta.
 */
@Component
public class ExtractionCheckEngine {

    private final BigDecimal amountTolerance;
    private final BigDecimal weightTolerance;

    public ExtractionCheckEngine(TicketingProperties properties) {
        this.amountTolerance = properties.validation().amountTolerance();
        this.weightTolerance = properties.validation().weightAmountTolerance();
    }

    public CheckReport evaluate(ExtractedTicket ticket, Store store, List<StoreTaxLetter> taxLetters) {
        List<ExtractedLineItem> lines = ticket.lineItems() == null ? List.of() : ticket.lineItems();
        List<CheckReport.CheckOutcome> outcomes = new ArrayList<>();
        List<CheckReport.LineFinding> findings = new ArrayList<>();

        outcomes.add(checkTotal(ticket, lines, findings));
        outcomes.add(checkLineArithmetic(lines, findings));
        outcomes.add(checkTaxLetterBases(ticket, lines, store, taxLetters, findings));
        outcomes.add(checkArticleCount(ticket, lines, store, findings));
        outcomes.add(checkTaxBreakdownAgainstTotal(ticket, findings));
        outcomes.add(checkGeneralDiscountsAgainstAmountPaid(ticket, findings));
        outcomes.add(checkSoldByPlausibility(lines, findings));
        outcomes.add(checkRowTextCarriesAmount(lines, findings));

        int covered = (int) lines.stream().filter(l -> isCovered(l, store)).count();
        BigDecimal ratio = lines.isEmpty()
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(covered)
                        .divide(BigDecimal.valueOf(lines.size()), 4, RoundingMode.HALF_UP);

        return new CheckReport(outcomes, findings, lines.size(), covered, ratio);
    }

    /**
     * Una línea está cubierta si alguna comprobación ha podido decir algo
     * <em>no trivial</em> sobre ella.
     *
     * <p>Con cantidad 1, {@code cantidad x precio = importe} se cumple siempre
     * que el precio unitario iguale al importe, así que no distingue una lectura
     * buena de una mala: es una tautología. Y el modelo rellena {@code unit_price}
     * en todas las líneas aunque el ticket no lo imprima, con lo que contar
     * "tiene precio unitario" daría una cobertura del 100 % en un Mercadona donde
     * en realidad solo 6 de 26 líneas se pueden comprobar. Inflar la cobertura es
     * peor que no medirla: invita a revisar menos justo donde no hay red.
     */
    private boolean isCovered(ExtractedLineItem line, Store store) {
        boolean discriminating = line.unitPrice() != null
                && (line.weight() != null
                    || (line.quantity() != null && line.quantity().compareTo(BigDecimal.ONE) > 0));
        boolean byTaxLetter = store.isHasLineTaxLetter() && line.taxLetter() != null;
        return discriminating || byTaxLetter;
    }

    // ------------------------------------------------------------------
    // C1 — suma de líneas contra el total impreso
    // ------------------------------------------------------------------

    private CheckReport.CheckOutcome checkTotal(ExtractedTicket ticket,
                                                List<ExtractedLineItem> lines,
                                                List<CheckReport.LineFinding> findings) {
        BigDecimal total = ticket.totals() == null ? null : ticket.totals().total();
        if (total == null || lines.isEmpty()) {
            return CheckReport.CheckOutcome.notApplicable(CheckCode.C1, "el ticket no trae total o no trae líneas");
        }

        BigDecimal sum = lines.stream()
                .map(ExtractedLineItem::lineTotal)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        boolean ok = within(sum, total, amountTolerance);
        if (!ok) {
            findings.add(new CheckReport.LineFinding(null, CheckCode.C1, IssueSeverity.ERROR,
                    "La suma de las líneas no cuadra con el total impreso", total, sum));
        }
        return CheckReport.CheckOutcome.of(CheckCode.C1, ok, lines.size(),
                "suma " + scale2(sum) + " contra total " + scale2(total));
    }

    // ------------------------------------------------------------------
    // C2 — cantidad x precio unitario contra el importe de línea
    // ------------------------------------------------------------------

    private CheckReport.CheckOutcome checkLineArithmetic(List<ExtractedLineItem> lines,
                                                         List<CheckReport.LineFinding> findings) {
        int evaluated = 0;
        int discriminating = 0;
        int failed = 0;

        for (int i = 0; i < lines.size(); i++) {
            ExtractedLineItem line = lines.get(i);
            if (line.unitPrice() == null || line.quantity() == null || line.lineTotal() == null) {
                continue;
            }

            // Línea a peso sin peso leído: el precio unitario es €/kg, así que
            // multiplicarlo por la cantidad no significa nada. Antes se hacía
            // igual y salía un error inventado en una línea correcta — "1 x 3,05
            // no da 3,30" cuando en el papel pone 1,082 kg y la cuenta cuadra.
            if ("weight".equals(line.soldBy()) && line.weight() == null) {
                findings.add(new CheckReport.LineFinding(i + 1, CheckCode.C2, IssueSeverity.WARN,
                        "\"" + line.rawDescription() + "\" se vende a peso pero no se ha leído el "
                                + "peso. Escríbelo en la línea y el precio por kilo entrará en la "
                                + "comparación; sin él, esta compra no es comparable entre súper.",
                        null, null));
                continue;
            }

            evaluated++;
            // Con cantidad 1 la igualdad es tautológica: se evalúa igual, pero no
            // se apunta como comprobación conseguida.
            if (line.weight() != null || line.quantity().compareTo(BigDecimal.ONE) > 0) {
                discriminating++;
            }

            BigDecimal base = line.weight() != null && line.weight().value() != null
                    ? line.weight().value()
                    : line.quantity();
            BigDecimal expected = base.multiply(line.unitPrice());
            // Los productos a peso redondean: 1,394 kg x 3,05 = 4,2517 -> 4,25.
            BigDecimal tolerance = line.weight() != null ? weightTolerance : amountTolerance;

            if (!within(expected, line.lineTotal(), tolerance)) {
                failed++;
                findings.add(new CheckReport.LineFinding(i + 1, CheckCode.C2, IssueSeverity.ERROR,
                        "\"" + line.rawDescription() + "\": " + scale2(base) + " x "
                                + scale2(line.unitPrice()) + " no da " + scale2(line.lineTotal())
                                + ". Alguno de los tres números viene de otra fila: normalmente "
                                + "la cantidad o el importe.",
                        scale2(expected), scale2(line.lineTotal())));
            }
        }

        if (evaluated == 0) {
            return CheckReport.CheckOutcome.notApplicable(CheckCode.C2,
                    "ninguna línea trae precio unitario impreso");
        }
        return CheckReport.CheckOutcome.of(CheckCode.C2, failed == 0, discriminating,
                discriminating + " de " + lines.size() + " líneas comprobables de verdad "
                        + "(cantidad > 1 o a peso); " + failed + " fallan de " + evaluated + " evaluadas");
    }

    // ------------------------------------------------------------------
    // C3 — importes por letra de IVA contra el desglose impreso
    // ------------------------------------------------------------------

    /**
     * Compara el bruto de cada letra contra <em>base + cuota</em> impresas, no
     * contra la base sola.
     *
     * <p>La base sola no vale porque el TPV no la calcula como
     * {@code bruto / (1+tipo)}: redondea por línea y reparte el residuo entre
     * base y cuota como le conviene. Un Cash Fresh real imprime al 10 % base
     * 25,97 y cuota 2,58 para un bruto de 28,55, y 25,97 × 10 % son 2,60: sus
     * propias cifras no cumplen el tipo nominal. Dividir daba 25,95 y la
     * comprobación fallaba con la lectura perfecta.
     *
     * <p>Lo que el TPV sí respeta siempre es que base + cuota devuelve el bruto
     * del bloque al céntimo, que además es justo lo que cambia cuando una línea
     * lleva la letra equivocada. Así la tolerancia vuelve a servir para detectar
     * errores en vez de gastarse en absorber el redondeo ajeno.
     *
     * <p>Si el desglose no trae cuota se cae al camino antiguo, y entonces hay
     * que ensanchar la tolerancia con el residuo que cada línea puede aportar.
     */
    private CheckReport.CheckOutcome checkTaxLetterBases(ExtractedTicket ticket,
                                                         List<ExtractedLineItem> lines,
                                                         Store store,
                                                         List<StoreTaxLetter> taxLetters,
                                                         List<CheckReport.LineFinding> findings) {
        if (!store.isHasLineTaxLetter()) {
            return CheckReport.CheckOutcome.notApplicable(CheckCode.C3,
                    store.getName() + " no imprime letra de IVA por línea");
        }
        List<ExtractedTaxBreakdown> breakdown = breakdownOf(ticket);
        if (breakdown.isEmpty() || taxLetters.isEmpty()) {
            return CheckReport.CheckOutcome.notApplicable(CheckCode.C3,
                    "falta el desglose de IVA o los tipos por letra del súper");
        }

        Map<String, LetterTotal> byLetter = new LinkedHashMap<>();
        int covered = 0;
        for (ExtractedLineItem line : lines) {
            if (line.taxLetter() == null || line.lineTotal() == null) {
                continue;
            }
            covered++;
            byLetter.merge(line.taxLetter().toUpperCase(), new LetterTotal(line.lineTotal(), 1),
                    LetterTotal::plus);
        }
        if (byLetter.isEmpty()) {
            return CheckReport.CheckOutcome.notApplicable(CheckCode.C3,
                    "la extracción no asignó letra de IVA a ninguna línea");
        }
        if (byLetter.size() == 1) {
            // Con una sola letra, cualquier permutación de descripciones da los
            // mismos totales por letra: la comprobación no distingue nada.
            return CheckReport.CheckOutcome.notApplicable(CheckCode.C3,
                    "todas las líneas comparten la letra " + byLetter.keySet().iterator().next()
                            + ", así que no desambigua el emparejamiento");
        }

        int failed = 0;
        StringBuilder detail = new StringBuilder();
        for (Map.Entry<String, LetterTotal> entry : byLetter.entrySet()) {
            Optional<BigDecimal> rate = taxLetters.stream()
                    .filter(l -> l.getLetter().equalsIgnoreCase(entry.getKey()))
                    .map(StoreTaxLetter::getRate)
                    .findFirst();
            if (rate.isEmpty()) {
                failed++;
                findings.add(new CheckReport.LineFinding(null, CheckCode.C3, IssueSeverity.ERROR,
                        "Letra de IVA desconocida en este súper: " + entry.getKey(), null, null));
                continue;
            }

            Optional<ExtractedTaxBreakdown> row = breakdown.stream()
                    .filter(b -> b.rate() != null && within(normalizeRate(b.rate()), rate.get(), new BigDecimal("0.0001")))
                    .findFirst();

            if (row.isEmpty() || row.get().base() == null) {
                failed++;
                findings.add(new CheckReport.LineFinding(null, CheckCode.C3, IssueSeverity.WARN,
                        "El desglose no trae ninguna base al " + rate.get() + " para la letra "
                                + entry.getKey(), null, entry.getValue().sum()));
                continue;
            }

            ExtractedTaxBreakdown printedRow = row.get();
            boolean grossComparison = printedRow.tax() != null;

            BigDecimal computed;
            BigDecimal printed;
            BigDecimal tolerance;
            String magnitude;
            if (grossComparison) {
                computed = entry.getValue().sum();
                printed = printedRow.base().add(printedRow.tax());
                tolerance = amountTolerance;
                magnitude = "de bruto";
            } else {
                computed = entry.getValue().sum()
                        .divide(BigDecimal.ONE.add(rate.get()), 2, RoundingMode.HALF_UP);
                printed = printedRow.base();
                // Medio céntimo por línea: el redondeo que el TPV puede haberse
                // guardado en cada una y que aquí no hay cuota con la que anular.
                tolerance = amountTolerance.add(new BigDecimal("0.005")
                        .multiply(BigDecimal.valueOf(entry.getValue().lines())));
                magnitude = "de base";
            }

            boolean ok = within(computed, printed, tolerance);
            if (!ok) {
                failed++;
                findings.add(new CheckReport.LineFinding(null, CheckCode.C3, IssueSeverity.ERROR,
                        "Las líneas con letra " + entry.getKey() + " suman " + scale2(computed)
                                + " " + magnitude + " y el ticket imprime " + scale2(printed)
                                + ". Alguna línea tiene la letra equivocada, que es el síntoma "
                                + "del desplazamiento de columnas.",
                        printed, computed));
            }
            detail.append(entry.getKey()).append("=").append(scale2(computed))
                    .append(" contra ").append(scale2(printed))
                    .append(grossComparison ? "" : " (el desglose no trae cuota)")
                    .append(ok ? " ok; " : " FALLA; ");
        }

        return CheckReport.CheckOutcome.of(CheckCode.C3, failed == 0, covered, detail.toString().trim());
    }

    // ------------------------------------------------------------------
    // C4 — recuento de artículos
    // ------------------------------------------------------------------

    private CheckReport.CheckOutcome checkArticleCount(ExtractedTicket ticket,
                                                       List<ExtractedLineItem> lines,
                                                       Store store,
                                                       List<CheckReport.LineFinding> findings) {
        if (!store.isHasArticleCount()) {
            return CheckReport.CheckOutcome.notApplicable(CheckCode.C4,
                    store.getName() + " no imprime recuento de artículos");
        }
        if (ticket.articleCount() == null) {
            return CheckReport.CheckOutcome.notApplicable(CheckCode.C4,
                    "la extracción no encontró el recuento de artículos");
        }

        BigDecimal sum = lines.stream()
                .map(ExtractedLineItem::quantity)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal printed = BigDecimal.valueOf(ticket.articleCount());

        boolean ok = within(sum, printed, new BigDecimal("0.001"));
        if (!ok) {
            findings.add(new CheckReport.LineFinding(null, CheckCode.C4, IssueSeverity.ERROR,
                    "Las cantidades suman " + scale2(sum) + " y el ticket dice "
                            + ticket.articleCount() + " artículos: falta o sobra alguna línea.",
                    printed, sum));
        }
        return CheckReport.CheckOutcome.of(CheckCode.C4, ok, lines.size(),
                "cantidades " + scale2(sum) + " contra " + ticket.articleCount() + " artículos");
    }

    // ------------------------------------------------------------------
    // C5 — bases más cuotas contra el total
    // ------------------------------------------------------------------

    private CheckReport.CheckOutcome checkTaxBreakdownAgainstTotal(ExtractedTicket ticket,
                                                                   List<CheckReport.LineFinding> findings) {
        List<ExtractedTaxBreakdown> breakdown = breakdownOf(ticket);
        BigDecimal total = ticket.totals() == null ? null : ticket.totals().total();
        if (breakdown.isEmpty() || total == null) {
            return CheckReport.CheckOutcome.notApplicable(CheckCode.C5,
                    "el ticket no trae desglose de IVA o no trae total");
        }

        BigDecimal sum = BigDecimal.ZERO;
        for (ExtractedTaxBreakdown row : breakdown) {
            if (row.base() != null) {
                sum = sum.add(row.base());
            }
            if (row.tax() != null) {
                sum = sum.add(row.tax());
            }
        }

        boolean ok = within(sum, total, amountTolerance);
        if (!ok) {
            findings.add(new CheckReport.LineFinding(null, CheckCode.C5, IssueSeverity.ERROR,
                    "Bases más cuotas suman " + scale2(sum) + " y el total impreso es "
                            + scale2(total) + ": la tabla de IVA está mal leída.", total, sum));
        }
        return CheckReport.CheckOutcome.of(CheckCode.C5, ok, 0,
                "bases+cuotas " + scale2(sum) + " contra total " + scale2(total));
    }

    // ------------------------------------------------------------------
    // C6 — total de compra menos descuentos generales contra total pagado
    // ------------------------------------------------------------------

    private CheckReport.CheckOutcome checkGeneralDiscountsAgainstAmountPaid(
            ExtractedTicket ticket, List<CheckReport.LineFinding> findings) {
        if (ticket.totals() == null) {
            return CheckReport.CheckOutcome.notApplicable(CheckCode.C6,
                    "el ticket no trae totales");
        }
        BigDecimal total = ticket.totals().total();
        BigDecimal amountPaid = ticket.totals().amountPaid();
        if (total == null || amountPaid == null) {
            return CheckReport.CheckOutcome.notApplicable(CheckCode.C6,
                    "falta el total de compra o el total pagado");
        }

        List<ExtractedTicket.ExtractedGeneralDiscount> discounts = Optional
                .ofNullable(ticket.totals().generalDiscounts()).orElse(List.of());
        if (discounts.isEmpty() && within(total, amountPaid, amountTolerance)) {
            return CheckReport.CheckOutcome.notApplicable(CheckCode.C6,
                    "el ticket no trae descuentos generales");
        }

        BigDecimal discountTotal = discounts.stream()
                .filter(java.util.Objects::nonNull)
                .map(ExtractedTicket.ExtractedGeneralDiscount::amount)
                .filter(java.util.Objects::nonNull)
                .map(BigDecimal::abs)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal expectedPaid = total.subtract(discountTotal);
        boolean ok = within(expectedPaid, amountPaid, amountTolerance);

        if (!ok) {
            findings.add(new CheckReport.LineFinding(null, CheckCode.C6, IssueSeverity.ERROR,
                    "El total de compra menos los descuentos generales da "
                            + scale2(expectedPaid) + ", pero el ticket imprime "
                            + scale2(amountPaid) + " como total pagado.", expectedPaid, amountPaid));
        }
        return CheckReport.CheckOutcome.of(CheckCode.C6, ok, 0,
                scale2(total) + " - " + scale2(discountTotal) + " = " + scale2(amountPaid));
    }

    // ------------------------------------------------------------------
    // H3 — plausibilidad de sold_by
    // ------------------------------------------------------------------

    /**
     * Ninguna comprobación aritmética mira {@code sold_by}, así que una mala
     * clasificación pasa desapercibida y deja el ticket entero fuera del precio
     * normalizado y del conteo de subidas.
     *
     * <p>Solo se sospecha de "weight" y "piece_variable", que son excepciones por
     * naturaleza. Un ticket entero en "unit" es lo normal — el de Cash Fresh lo
     * es y está bien clasificado — así que marcarlo daría un falso positivo en
     * la mayoría de los tickets.
     */
    private CheckReport.CheckOutcome checkSoldByPlausibility(List<ExtractedLineItem> lines,
                                                             List<CheckReport.LineFinding> findings) {
        List<String> values = lines.stream()
                .map(ExtractedLineItem::soldBy)
                .filter(java.util.Objects::nonNull)
                .toList();
        if (values.size() < 3) {
            return CheckReport.CheckOutcome.notApplicable(CheckCode.H3,
                    "hacen falta al menos 3 líneas clasificadas para que la señal signifique algo");
        }

        String first = values.getFirst();
        boolean uniform = values.stream().allMatch(first::equals);
        boolean exceptional = "weight".equals(first) || "piece_variable".equals(first);

        if (uniform && exceptional) {
            findings.add(new CheckReport.LineFinding(null, CheckCode.H3, IssueSeverity.WARN,
                    "Las " + values.size() + " líneas salen clasificadas como \"" + first
                            + "\", que es la excepción y no lo normal. Casi seguro que están mal "
                            + "clasificadas: revísalas antes de validar, porque así el ticket "
                            + "entero queda fuera del precio normalizado.", null, null));
            return CheckReport.CheckOutcome.of(CheckCode.H3, false, values.size(),
                    "todas las líneas en \"" + first + "\"");
        }
        return CheckReport.CheckOutcome.of(CheckCode.H3, true, values.size(),
                uniform ? "todas en \"" + first + "\", que es plausible" : "clasificación variada");
    }

    // ------------------------------------------------------------------
    // H4 — la fila transcrita lleva el importe
    // ------------------------------------------------------------------

    /**
     * La transcripción en dos etapas solo protege del desplazamiento de columnas
     * si {@code raw_row_text} lleva la fila entera. Si trae solo la descripción,
     * el importe se ha leído por separado y la defensa no está en efecto, aunque
     * el resto de comprobaciones pasen.
     */
    private CheckReport.CheckOutcome checkRowTextCarriesAmount(List<ExtractedLineItem> lines,
                                                               List<CheckReport.LineFinding> findings) {
        List<ExtractedLineItem> withRowText = lines.stream()
                .filter(l -> l.rawRowText() != null && !l.rawRowText().isBlank() && l.lineTotal() != null)
                .toList();
        if (withRowText.isEmpty()) {
            return CheckReport.CheckOutcome.notApplicable(CheckCode.H4,
                    "la extracción no transcribió ninguna fila");
        }

        int missing = 0;
        for (ExtractedLineItem line : withRowText) {
            if (!rowTextContainsAmount(line)) {
                missing++;
            }
        }

        if (missing > 0) {
            findings.add(new CheckReport.LineFinding(null, CheckCode.H4, IssueSeverity.WARN,
                    missing + " de " + withRowText.size() + " filas transcritas no contienen su "
                            + "importe, así que se leyó por separado de la descripción. La "
                            + "protección contra el desplazamiento de columnas no está actuando "
                            + "en esas líneas.", null, null));
        }
        return CheckReport.CheckOutcome.of(CheckCode.H4, missing == 0, withRowText.size() - missing,
                (withRowText.size() - missing) + " de " + withRowText.size()
                        + " filas transcritas incluyen su importe");
    }

    /** El importe puede estar impreso con coma o con punto según el súper. */
    private boolean rowTextContainsAmount(ExtractedLineItem line) {
        String row = line.rawRowText();
        String withDot = scale2(line.lineTotal()).toPlainString();
        return row.contains(withDot) || row.contains(withDot.replace('.', ','));
    }

    // ------------------------------------------------------------------

    /**
     * Bruto acumulado de una letra y cuántas líneas lo forman. El recuento solo
     * hace falta para dimensionar la tolerancia cuando C3 no puede comparar
     * contra base + cuota.
     */
    private record LetterTotal(BigDecimal sum, int lines) {
        LetterTotal plus(LetterTotal other) {
            return new LetterTotal(sum.add(other.sum()), lines + other.lines());
        }
    }

    private List<ExtractedTaxBreakdown> breakdownOf(ExtractedTicket ticket) {
        if (ticket.totals() == null || ticket.totals().taxBreakdown() == null) {
            return List.of();
        }
        return ticket.totals().taxBreakdown();
    }

    /**
     * El modelo devuelve el tipo unas veces como fracción (0.21) y otras como
     * porcentaje (21) por mucho que el prompt lo pida en fracción. Un IVA del
     * 2100 % no existe, así que la corrección es inequívoca.
     */
    private BigDecimal normalizeRate(BigDecimal rate) {
        return rate.compareTo(BigDecimal.ONE) > 0
                ? rate.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)
                : rate;
    }

    private boolean within(BigDecimal a, BigDecimal b, BigDecimal tolerance) {
        return a.subtract(b).abs().compareTo(tolerance) <= 0;
    }

    private BigDecimal scale2(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
