package com.juanluidos.ticketing.ingest;

import com.juanluidos.ticketing.domain.*;
import com.juanluidos.ticketing.extraction.ExtractedTicket;
import com.juanluidos.ticketing.matching.ProductMatcher;
import com.juanluidos.ticketing.repository.*;
import com.juanluidos.ticketing.validation.CheckReport;
import com.juanluidos.ticketing.validation.ExtractionCheckEngine;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Los tramos transaccionales de la extracción, separados del trabajo largo.
 *
 * <p>Están en su propia clase justo para eso: la llamada al modelo tarda minutos
 * y no puede ocurrir dentro de una transacción abierta. El job marca el estado,
 * suelta la transacción, llama a Ollama, y vuelve aquí a guardar.
 */
@Component
public class TicketExtractionStore {

    private final TicketRepository tickets;
    private final LineItemRepository lineItems;
    private final TicketTaxSummaryRepository taxSummaries;
    private final TicketCheckResultRepository checkResults;
    private final ValidationIssueRepository issues;
    private final StoreRepository stores;
    private final StoreTaxLetterRepository taxLetters;
    private final ProductMatcher matcher;
    private final ExtractionCheckEngine checkEngine;

    public TicketExtractionStore(TicketRepository tickets, LineItemRepository lineItems,
                                 TicketTaxSummaryRepository taxSummaries,
                                 TicketCheckResultRepository checkResults,
                                 ValidationIssueRepository issues, StoreRepository stores,
                                 StoreTaxLetterRepository taxLetters, ProductMatcher matcher,
                                 ExtractionCheckEngine checkEngine) {
        this.tickets = tickets;
        this.lineItems = lineItems;
        this.taxSummaries = taxSummaries;
        this.checkResults = checkResults;
        this.issues = issues;
        this.stores = stores;
        this.taxLetters = taxLetters;
        this.matcher = matcher;
        this.checkEngine = checkEngine;
    }

    public record ExtractionInput(Long ticketId, String imagePath, Store store) {
    }

    @Transactional
    public ExtractionInput begin(Long ticketId, String storeCodeHint) {
        Ticket ticket = tickets.findById(ticketId).orElseThrow();
        ticket.setStatus(TicketStatus.EXTRACTING);
        ticket.setExtractionStartedAt(LocalDateTime.now());
        ticket.setExtractionError(null);
        tickets.save(ticket);

        // Se recarga por id en vez de usar ticket.getStore() directamente: eso
        // devuelve un proxy perezoso, y el constructor del prompt lo lee DESPUÉS
        // de que esta transacción se cierre. findById da una instancia con los
        // campos ya cargados, que sobrevive al cierre de sesión.
        //
        // Si no hay pista se devuelve null, y el prompt sale genérico. Coger un
        // súper cualquiera "para tener algo" era peor: al ticket de Cash Fresh se
        // le aplicaron las reglas de Xinya y perdió las letras de IVA.
        Store store = Optional.ofNullable(ticket.getStore())
                .map(Store::getId)
                .flatMap(stores::findById)
                .or(() -> storeCodeHint == null ? Optional.empty() : stores.findByCode(storeCodeHint))
                .orElse(null);

        // Si el súper viene elegido a mano, se fija YA en el ticket. Así queda
        // claro que es una decisión de la persona y no una conjetura, y al
        // guardar no se sustituye por lo que el modelo crea haber leído.
        if (store != null && ticket.getStore() == null) {
            ticket.setStore(store);
            tickets.save(ticket);
        }
        return new ExtractionInput(ticketId, ticket.getImagePath(), store);
    }

    @Transactional
    public void saveFailure(Long ticketId, String error) {
        Ticket ticket = tickets.findById(ticketId).orElseThrow();
        ticket.setStatus(TicketStatus.EXTRACTION_ERROR);
        ticket.setExtractionFinishedAt(LocalDateTime.now());
        ticket.setExtractionError(error);
        tickets.save(ticket);
    }

    /**
     * @return el código del súper con el que conviene reextraer, si la cabecera
     *         ha revelado uno cuyas reglas de layout no se usaron en esta pasada.
     *         La primera pasada de un ticket va con prompt genérico, así que esta
     *         segunda es la que aplica las reglas buenas. No hay riesgo de bucle:
     *         al guardar queda el súper fijado en el ticket y la siguiente pasada
     *         ya arranca con él.
     */
    @Transactional
    public Optional<String> saveSuccess(Long ticketId, ExtractedTicket extracted, String rawJson,
                                        String model, Store storeUsedForPrompt) {
        Ticket ticket = tickets.findById(ticketId).orElseThrow();

        // Lo que ya esté puesto MANDA sobre lo que el modelo crea haber leído.
        // El CIF vive en letra diminuta al pie del ticket y a veces ni se llega
        // a él: un Cash Fresh salió etiquetado como Xinya, y con el súper
        // equivocado hasta las comprobaciones mienten — C4 pasó a "aplicable"
        // sin serlo y dio verde contra un recuento de artículos inventado.
        Store store = ticket.getStore() != null
                ? ticket.getStore()
                : detectStore(extracted).orElse(null);
        ticket.setStore(store);
        boolean layoutRulesWereWrong = store != null
                && (storeUsedForPrompt == null || !store.getId().equals(storeUsedForPrompt.getId()));

        String receiptNumber = blankToNull(extracted.receiptNumber());
        Optional<Ticket> duplicate = receiptNumber == null || store == null
                ? Optional.empty()
                : tickets.findByStoreIdAndReceiptNumber(store.getId(), receiptNumber)
                        .filter(other -> !other.getId().equals(ticketId));
        if (duplicate.isPresent()) {
            // No se guarda el número: dejarlo pondría a la unique en la
            // situación de reventar con un error opaco en vez de decir esto.
            ticket.setStatus(TicketStatus.EXTRACTION_ERROR);
            ticket.setExtractionFinishedAt(LocalDateTime.now());
            ticket.setExtractionError("Este ticket ya está registrado: número "
                    + receiptNumber + ", ticket #" + duplicate.get().getId());
            ticket.setRawExtraction(rawJson);
            tickets.save(ticket);
            return Optional.empty();
        }

        ticket.setReceiptNumber(receiptNumber);
        ticket.setPurchasedAt(ReceiptDateTimeParser.parse(extracted.purchasedAt()));
        ticket.setTotal(extracted.totals() == null ? null : extracted.totals().total());
        ticket.setArticleCount(extracted.articleCount());
        if (extracted.currency() != null && extracted.currency().length() == 3) {
            ticket.setCurrency(extracted.currency().toUpperCase());
        }
        ticket.setRawExtraction(rawJson);
        ticket.setExtractionModel(model);
        ticket.setExtractionFinishedAt(LocalDateTime.now());

        // Reextraer sustituye lo anterior; si no, se acumularían líneas de la
        // pasada previa.
        issues.deleteByTicketId(ticketId);
        checkResults.deleteByTicketId(ticketId);
        lineItems.deleteAll(lineItems.findByTicketIdOrderByLineNoAsc(ticketId));
        lineItems.flush();

        List<LineItem> saved = saveLines(ticket, store, extracted);
        saveTaxSummary(ticket, extracted);

        CheckReport report = checkEngine.evaluate(extracted, store,
                store == null ? List.of() : taxLetters.findByStoreId(store.getId()));
        saveReport(ticket, saved, report);

        ticket.setCoverageRatio(report.coverageRatio());
        ticket.setStatus(TicketStatus.EXTRACTED);
        tickets.save(ticket);

        return layoutRulesWereWrong ? Optional.of(store.getCode()) : Optional.empty();
    }

    private List<LineItem> saveLines(Ticket ticket, Store store, ExtractedTicket extracted) {
        List<LineItem> result = new java.util.ArrayList<>();
        List<ExtractedTicket.ExtractedLineItem> lines =
                extracted.lineItems() == null ? List.of() : extracted.lineItems();

        for (int i = 0; i < lines.size(); i++) {
            ExtractedTicket.ExtractedLineItem source = lines.get(i);
            LineItem line = new LineItem();
            line.setTicket(ticket);
            line.setLineNo(i + 1);
            line.setRawRowText(source.rawRowText());
            line.setRawDescription(source.rawDescription() == null ? "" : source.rawDescription());
            line.setQuantity(source.quantity() == null ? BigDecimal.ONE : source.quantity());
            line.setSoldBy(mapSoldBy(source.soldBy()));
            if (source.weight() != null) {
                line.setWeightValue(source.weight().value());
                line.setWeightUnit(source.weight().unit());
            }
            line.setPrintedUnitPrice(source.unitPrice());
            line.setPrintedUnitPriceUnit(source.unitPriceUnit());
            line.setLineTotal(source.lineTotal() == null ? BigDecimal.ZERO : source.lineTotal());
            recoverWeightFromQuantity(line);
            line.setTaxLetter(blankToNull(source.taxLetter()));
            line.setPromo(Boolean.TRUE.equals(source.isPromo()));
            line.setPromoNote(blankToNull(source.promoNote()));

            // Sugerencia de producto, para que la pantalla de validación llegue
            // con el emparejamiento propuesto y la persona solo confirme.
            if (store != null) {
                matcher.suggest(store.getId(), line.getRawDescription()).ifPresent(s -> {
                    line.setStoreProduct(s.product());
                    line.setMatchConfidence(s.confidence());
                    line.setMatchMethod(s.method());
                });
            }
            result.add(lineItems.save(line));
        }
        return result;
    }

    /**
     * Recupera el peso cuando el modelo lo ha metido en la casilla de cantidad.
     *
     * <p>Cash Fresh imprime los productos a peso con el mismo formato que las
     * cantidades: "QUESO CABRA PAYOYA  0,260x 31,95  8,31". El modelo pone
     * 0,260 en quantity y deja weight vacío, lo cual es una lectura razonable
     * del papel — pero entonces no hay precio por kilo y esa compra se queda
     * fuera de la comparación.
     *
     * <p>La conversión solo se hace cuando la aritmética la demuestra: el precio
     * unitario está en unidad de peso y cantidad por precio da el importe. Si la
     * cuenta cuadra, ese número es un peso y no una cantidad de unidades.
     */
    private void recoverWeightFromQuantity(LineItem line) {
        if (line.getSoldBy() != SoldBy.WEIGHT || line.getWeightValue() != null) {
            return;
        }
        BigDecimal quantity = line.getQuantity();
        BigDecimal unitPrice = line.getPrintedUnitPrice();
        String unit = line.getPrintedUnitPriceUnit();
        if (quantity == null || unitPrice == null || unit == null
                || WEIGHT_UNITS.stream().noneMatch(unit::equalsIgnoreCase)) {
            return;
        }

        BigDecimal expected = quantity.multiply(unitPrice);
        if (expected.subtract(line.getLineTotal()).abs().compareTo(new BigDecimal("0.02")) > 0) {
            return;
        }

        line.setWeightValue(quantity);
        line.setWeightUnit(unit.toLowerCase());
        // A peso, la cantidad es una: se compró UNA pieza que pesa eso.
        line.setQuantity(BigDecimal.ONE);
    }

    private static final List<String> WEIGHT_UNITS = List.of("kg", "g", "gr");

    private void saveTaxSummary(Ticket ticket, ExtractedTicket extracted) {
        taxSummaries.deleteAll(taxSummaries.findByTicketId(ticket.getId()));
        if (extracted.totals() == null || extracted.totals().taxBreakdown() == null) {
            return;
        }
        for (ExtractedTicket.ExtractedTaxBreakdown row : extracted.totals().taxBreakdown()) {
            if (row.rate() == null || row.base() == null || row.tax() == null) {
                continue;
            }
            TicketTaxSummary summary = new TicketTaxSummary();
            summary.setTicket(ticket);
            summary.setRate(normalizeRate(row.rate()));
            summary.setBaseAmount(row.base());
            summary.setTaxAmount(row.tax());
            taxSummaries.save(summary);
        }
    }

    private void saveReport(Ticket ticket, List<LineItem> lines, CheckReport report) {
        for (CheckReport.CheckOutcome outcome : report.outcomes()) {
            TicketCheckResult row = new TicketCheckResult();
            row.setTicket(ticket);
            row.setCheckCode(outcome.code());
            row.setApplicable(outcome.applicable());
            row.setPassed(outcome.passed());
            row.setLinesCovered(outcome.linesCovered());
            row.setDetail(truncate(outcome.detail(), 500));
            checkResults.save(row);
        }

        for (CheckReport.LineFinding finding : report.findings()) {
            ValidationIssue issue = new ValidationIssue();
            issue.setTicket(ticket);
            if (finding.lineNo() != null && finding.lineNo() >= 1 && finding.lineNo() <= lines.size()) {
                issue.setLineItem(lines.get(finding.lineNo() - 1));
            }
            issue.setCheckCode(finding.code());
            issue.setSeverity(finding.severity());
            issue.setMessage(truncate(finding.message(), 500));
            issue.setExpected(finding.expected());
            issue.setActual(finding.actual());
            issues.save(issue);
        }
    }

    /** Por el NIF/CIF de la cabecera, tolerando prefijos y guiones. */
    private Optional<Store> detectStore(ExtractedTicket extracted) {
        if (extracted.store() == null || extracted.store().nif() == null) {
            return Optional.empty();
        }
        String needle = onlyAlphanumeric(extracted.store().nif());
        if (needle.isBlank()) {
            return Optional.empty();
        }
        return stores.findAll().stream()
                .filter(s -> s.getTaxId() != null && onlyAlphanumeric(s.getTaxId()).equalsIgnoreCase(needle))
                .findFirst();
    }

    private String onlyAlphanumeric(String value) {
        return value.replaceAll("[^A-Za-z0-9]", "");
    }

    private SoldBy mapSoldBy(String value) {
        if (value == null) {
            return SoldBy.PACKAGE;
        }
        return switch (value) {
            case "weight" -> SoldBy.WEIGHT;
            case "piece_variable" -> SoldBy.VARIABLE_PIECE;
            default -> SoldBy.PACKAGE;
        };
    }

    /** El modelo alterna fracción y porcentaje; un IVA del 2100 % no existe. */
    private BigDecimal normalizeRate(BigDecimal rate) {
        return rate.compareTo(BigDecimal.ONE) > 0
                ? rate.divide(BigDecimal.valueOf(100), 4, java.math.RoundingMode.HALF_UP)
                : rate;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }
}
