package com.juanluidos.ticketing.validation;

import com.juanluidos.ticketing.domain.*;
import com.juanluidos.ticketing.extraction.ExtractedTicket;
import com.juanluidos.ticketing.matching.ProductMatcher;
import com.juanluidos.ticketing.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Aplica las correcciones de la pantalla de validación y, si se confirma, cierra
 * el ticket generando la serie de precios.
 *
 * <p>La revisión humana es estructural, no opcional: el desplazamiento de
 * columnas es el modo de fallo dominante del extractor y hay variantes que
 * ninguna comprobación detecta. Por eso solo desde aquí se llega a
 * {@link TicketStatus#VALIDATED} y solo desde aquí nacen las
 * {@link PriceObservation}.
 */
@Service
public class TicketValidationService {

    private final TicketRepository tickets;
    private final LineItemRepository lineItems;
    private final TicketTaxSummaryRepository taxSummaries;
    private final TicketCheckResultRepository checkResults;
    private final ValidationIssueRepository issues;
    private final PriceObservationRepository observations;
    private final StoreProductRepository products;
    private final CategoryRepository categories;
    private final StoreTaxLetterRepository taxLetters;
    private final ExtractionCheckEngine checkEngine;
    private final ProductMatcher matcher;
    private final PriceNormalizer normalizer;

    public TicketValidationService(TicketRepository tickets, LineItemRepository lineItems,
                                  TicketTaxSummaryRepository taxSummaries,
                                  TicketCheckResultRepository checkResults,
                                  ValidationIssueRepository issues,
                                  PriceObservationRepository observations,
                                  StoreProductRepository products, CategoryRepository categories,
                                  StoreTaxLetterRepository taxLetters,
                                  ExtractionCheckEngine checkEngine, ProductMatcher matcher,
                                  PriceNormalizer normalizer) {
        this.tickets = tickets;
        this.lineItems = lineItems;
        this.taxSummaries = taxSummaries;
        this.checkResults = checkResults;
        this.issues = issues;
        this.observations = observations;
        this.products = products;
        this.categories = categories;
        this.taxLetters = taxLetters;
        this.checkEngine = checkEngine;
        this.matcher = matcher;
        this.normalizer = normalizer;
    }

    @Transactional
    public void apply(Long ticketId, ValidationRequest request, String username) {
        Ticket ticket = tickets.findById(ticketId).orElseThrow();
        if (ticket.getStatus() == TicketStatus.EXTRACTING) {
            throw new IllegalStateException("El ticket se está extrayendo todavía");
        }

        applyTicketFields(ticket, request);
        List<LineItem> lines = applyLines(ticket, request);
        CheckReport report = reevaluate(ticket, lines);

        if (request.confirm()) {
            confirm(ticket, lines, report, username);
        }
        tickets.save(ticket);
    }

    // ------------------------------------------------------------------

    private void applyTicketFields(Ticket ticket, ValidationRequest request) {
        if (request.purchasedAt() != null) {
            ticket.setPurchasedAt(request.purchasedAt());
        }
        if (request.receiptNumber() != null) {
            ticket.setReceiptNumber(request.receiptNumber().isBlank() ? null : request.receiptNumber());
        }
        if (request.total() != null) {
            ticket.setTotal(request.total());
        }
        if (request.articleCount() != null) {
            ticket.setArticleCount(request.articleCount());
        }
    }

    private List<LineItem> applyLines(Ticket ticket, ValidationRequest request) {
        List<LineItem> current = lineItems.findByTicketIdOrderByLineNoAsc(ticket.getId());
        if (request.lines() == null) {
            return current;
        }

        for (ValidationRequest.LineUpdate update : request.lines()) {
            LineItem line = current.stream()
                    .filter(l -> l.getId().equals(update.lineItemId()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "La línea " + update.lineItemId() + " no es de este ticket"));

            if (Boolean.TRUE.equals(update.delete())) {
                observations.deleteByLineItemId(line.getId());
                observations.flush();
                lineItems.delete(line);
                continue;
            }

            if (update.rawDescription() != null) {
                line.setRawDescription(update.rawDescription());
            }
            if (update.quantity() != null) {
                line.setQuantity(update.quantity());
            }
            if (update.lineTotal() != null) {
                line.setLineTotal(update.lineTotal());
            }
            if (update.printedUnitPrice() != null) {
                line.setPrintedUnitPrice(update.printedUnitPrice());
            }
            if (update.taxLetter() != null) {
                line.setTaxLetter(update.taxLetter().isBlank() ? null : update.taxLetter());
            }
            if (update.soldBy() != null) {
                line.setSoldBy(update.soldBy());
            }
            if (update.promo() != null) {
                line.setPromo(update.promo());
            }
            if (update.weightValue() != null) {
                line.setWeightValue(update.weightValue());
                line.setWeightUnit(update.weightUnit());
            }
            applyProductDecision(ticket, line, update.product());
            lineItems.save(line);
        }
        return lineItems.findByTicketIdOrderByLineNoAsc(ticket.getId());
    }

    private void applyProductDecision(Ticket ticket, LineItem line,
                                      ValidationRequest.ProductDecision decision) {
        if (decision == null) {
            return;
        }
        if (decision.unassign()) {
            line.setStoreProduct(null);
            line.setMatchMethod(null);
            line.setMatchConfidence(null);
            return;
        }

        StoreProduct product = null;
        MatchMethod method = null;

        if (decision.existingStoreProductId() != null) {
            product = products.findById(decision.existingStoreProductId()).orElseThrow();
            method = MatchMethod.MANUAL;
        } else if (decision.newProduct() != null) {
            product = createProduct(ticket.getStore(), line, decision.newProduct());
            method = MatchMethod.NEW_PRODUCT;
        }

        if (product != null) {
            line.setStoreProduct(product);
            line.setMatchMethod(method);
            line.setMatchConfidence(BigDecimal.ONE);
            // Cada confirmación enseña al matcher una forma más de escribir el
            // mismo producto, así que la próxima vez ya sale sugerido.
            matcher.rememberAlias(product, ticket.getStore(), line.getRawDescription());
            rememberTaxLetter(product, line);
        }
    }

    private StoreProduct createProduct(Store store, LineItem line,
                                       ValidationRequest.NewProduct source) {
        String name = source.canonicalName() == null || source.canonicalName().isBlank()
                ? line.getRawDescription()
                : source.canonicalName();

        Optional<StoreProduct> existing = products.findByStoreIdAndCanonicalName(store.getId(), name);
        if (existing.isPresent()) {
            return existing.get();
        }

        StoreProduct product = new StoreProduct();
        product.setStore(store);
        product.setCanonicalName(name);
        product.setDisplayName(source.displayName());
        product.setNotes(source.notes());
        product.setBrand(source.brand());
        product.setPackageSize(source.packageSize());
        product.setPackageUnit(source.packageUnit());
        UnitConverter.dimensionOf(source.packageUnit()).ifPresent(product::setDimension);
        product.setSoldBy(source.soldBy() == null
                ? Optional.ofNullable(line.getSoldBy()).orElse(SoldBy.PACKAGE)
                : source.soldBy());
        if (source.categoryId() != null) {
            product.setCategory(categories.findById(source.categoryId()).orElse(null));
        }
        return products.save(product);
    }

    /** Alimenta la heurística H1: una letra distinta de la habitual delata desplazamiento. */
    private void rememberTaxLetter(StoreProduct product, LineItem line) {
        if (line.getTaxLetter() != null && product.getUsualTaxLetter() == null) {
            product.setUsualTaxLetter(line.getTaxLetter());
            products.save(product);
        }
    }

    // ------------------------------------------------------------------

    private CheckReport reevaluate(Ticket ticket, List<LineItem> lines) {
        List<TicketTaxSummary> taxes = taxSummaries.findByTicketId(ticket.getId());
        ExtractedTicket rebuilt = PersistedTicketMapper.toExtracted(ticket, lines, taxes);

        CheckReport report = checkEngine.evaluate(rebuilt, ticket.getStore(),
                ticket.getStore() == null ? List.of() : taxLetters.findByStoreId(ticket.getStore().getId()));

        issues.deleteByTicketId(ticket.getId());
        checkResults.deleteByTicketId(ticket.getId());
        issues.flush();
        checkResults.flush();

        for (CheckReport.CheckOutcome outcome : report.outcomes()) {
            TicketCheckResult row = new TicketCheckResult();
            row.setTicket(ticket);
            row.setCheckCode(outcome.code());
            row.setApplicable(outcome.applicable());
            row.setPassed(outcome.passed());
            row.setLinesCovered(outcome.linesCovered());
            row.setDetail(outcome.detail());
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
            issue.setMessage(finding.message());
            issue.setExpected(finding.expected());
            issue.setActual(finding.actual());
            issues.save(issue);
        }

        ticket.setCoverageRatio(report.coverageRatio());
        return report;
    }

    private void confirm(Ticket ticket, List<LineItem> lines, CheckReport report, String username) {
        List<String> blockers = new ArrayList<>();
        if (report.hasErrors()) {
            blockers.add("quedan " + report.findings().stream()
                    .filter(f -> f.severity() == IssueSeverity.ERROR).count()
                    + " comprobaciones en rojo");
        }
        long unmatched = lines.stream().filter(l -> l.getStoreProduct() == null).count();
        if (unmatched > 0) {
            blockers.add(unmatched + " líneas sin producto asignado");
        }
        if (ticket.getPurchasedAt() == null) {
            blockers.add("falta la fecha de compra");
        }
        if (!blockers.isEmpty()) {
            throw new IllegalStateException("No se puede validar: " + String.join("; ", blockers));
        }

        for (LineItem line : lines) {
            observations.deleteByLineItemId(line.getId());
        }
        observations.flush();
        lines.forEach(line -> observations.save(toObservation(ticket, line)));

        ticket.setStatus(TicketStatus.VALIDATED);
        ticket.setValidatedAt(LocalDateTime.now());
        ticket.setValidatedBy(ticket.getUser());
    }

    private PriceObservation toObservation(Ticket ticket, LineItem line) {
        StoreProduct product = line.getStoreProduct();
        SoldBy soldBy = Optional.ofNullable(line.getSoldBy()).orElse(product.getSoldBy());
        BigDecimal quantity = line.getQuantity() == null || line.getQuantity().signum() <= 0
                ? BigDecimal.ONE
                : line.getQuantity();

        PriceObservation observation = new PriceObservation();
        observation.setStoreProduct(product);
        observation.setLineItem(line);
        observation.setObservedAt(ticket.getPurchasedAt());
        observation.setQuantity(quantity);
        observation.setLineTotal(line.getLineTotal());
        observation.setPricePerPiece(line.getLineTotal().divide(quantity, 4, RoundingMode.HALF_UP));
        observation.setPromo(line.isPromo());

        normalizer.of(line.getLineTotal(), quantity, soldBy,
                        line.getWeightValue(), line.getWeightUnit(), product)
                .ifPresent(normalized -> {
                    observation.setNormalizedUnitPrice(normalized.price());
                    observation.setNormalizedUnit(normalized.unit());
                });

        // Una pieza de peso variable cambia de precio porque cambia de peso, no
        // porque haya subido: cuenta como precio pagado, no como serie de precios.
        boolean normalizable = observation.getNormalizedUnitPrice() != null;
        observation.setCountsForIncrease(
                normalizable && !line.isPromo() && soldBy != SoldBy.VARIABLE_PIECE);
        return observation;
    }
}
