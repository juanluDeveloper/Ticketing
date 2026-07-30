package com.juanluidos.ticketing.api;

import com.juanluidos.ticketing.domain.*;
import com.juanluidos.ticketing.matching.PackageSizeParser;
import com.juanluidos.ticketing.repository.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Component
public class TicketViewAssembler {

    private final TicketRepository tickets;
    private final LineItemRepository lineItems;
    private final TicketCheckResultRepository checkResults;
    private final ValidationIssueRepository issues;
    private final TicketTaxSummaryRepository taxSummaries;
    private final PackageSizeParser sizeParser;

    public TicketViewAssembler(TicketRepository tickets, LineItemRepository lineItems,
                               TicketCheckResultRepository checkResults,
                               ValidationIssueRepository issues,
                               TicketTaxSummaryRepository taxSummaries,
                               PackageSizeParser sizeParser) {
        this.tickets = tickets;
        this.lineItems = lineItems;
        this.checkResults = checkResults;
        this.issues = issues;
        this.taxSummaries = taxSummaries;
        this.sizeParser = sizeParser;
    }

    /**
     * Reciben el id y cargan dentro de la transacción a propósito. Cargar el
     * Ticket fuera y pasarlo aquí deja {@code ticket.getStore()} como un proxy
     * atado a una sesión ya cerrada, y revienta con LazyInitializationException
     * en cuanto se lee el nombre del súper.
     */
    @Transactional(readOnly = true)
    public TicketViews.TicketSummary summary(Long ticketId) {
        Ticket ticket = tickets.findById(ticketId).orElseThrow();
        return toSummary(ticket, lineItems.findByTicketIdOrderByLineNoAsc(ticketId).size());
    }

    @Transactional(readOnly = true)
    public List<TicketViews.TicketSummary> summaries(Long userId) {
        return tickets.findByUserIdOrderByPurchasedAtDesc(userId).stream()
                .map(t -> toSummary(t, lineItems.findByTicketIdOrderByLineNoAsc(t.getId()).size()))
                .toList();
    }

    @Transactional(readOnly = true)
    public TicketViews.TicketDetail detail(Long ticketId) {
        Ticket ticket = tickets.findById(ticketId).orElseThrow();
        List<LineItem> lines = lineItems.findByTicketIdOrderByLineNoAsc(ticket.getId());
        List<ValidationIssue> allIssues = issues.findByTicketId(ticket.getId());

        List<TicketViews.LineView> lineViews = lines.stream()
                .map(line -> toLineView(line, allIssues))
                .toList();

        List<TicketViews.IssueView> ticketLevel = allIssues.stream()
                .filter(i -> i.getLineItem() == null)
                .map(this::toIssueView)
                .toList();

        List<TicketViews.CheckView> checks = checkResults.findByTicketId(ticket.getId()).stream()
                .map(c -> new TicketViews.CheckView(
                        c.getCheckCode().name(), c.getCheckCode().getDescription(),
                        c.isApplicable(), c.getPassed(), c.getLinesCovered(), c.getDetail()))
                .sorted(java.util.Comparator.comparing(TicketViews.CheckView::code))
                .toList();

        List<TicketViews.TaxView> taxes = taxSummaries.findByTicketId(ticket.getId()).stream()
                .map(t -> new TicketViews.TaxView(
                        t.getRate(), t.getBaseAmount(), t.getTaxAmount(), t.getTaxLetter()))
                .toList();

        return new TicketViews.TicketDetail(
                toSummary(ticket, lines.size()), lineViews, checks, ticketLevel, taxes,
                coverageWarning(ticket, lines.size()));
    }

    /**
     * El aviso existe porque un ticket con todo en verde y cobertura baja necesita
     * MÁS atención, no menos: significa que las comprobaciones no han podido
     * decir casi nada y el emparejamiento nombre-importe está sin verificar.
     */
    private String coverageWarning(Ticket ticket, int lineCount) {
        if (ticket.getCoverageRatio() == null || lineCount == 0) {
            return null;
        }
        int covered = ticket.getCoverageRatio()
                .multiply(BigDecimal.valueOf(lineCount))
                .setScale(0, java.math.RoundingMode.HALF_UP)
                .intValue();
        int uncovered = lineCount - covered;
        if (uncovered <= 0) {
            return null;
        }
        return uncovered + " de " + lineCount + " líneas no tienen ninguna comprobación cruzada "
                + "detrás. Repasa el emparejamiento entre descripción e importe contra la foto: "
                + "los números pueden cuadrar con las descripciones desplazadas.";
    }

    private TicketViews.TicketSummary toSummary(Ticket ticket, int lineCount) {
        Store store = ticket.getStore();
        return new TicketViews.TicketSummary(
                ticket.getId(),
                ticket.getStatus().name(),
                store == null ? null : store.getCode(),
                store == null ? null : store.getName(),
                ticket.getPurchasedAt(),
                ticket.getReceiptNumber(),
                ticket.getTotal(),
                ticket.getArticleCount(),
                lineCount,
                ticket.getCoverageRatio(),
                ticket.getCreatedAt(),
                ticket.getExtractionError());
    }

    private TicketViews.LineView toLineView(LineItem line, List<ValidationIssue> allIssues) {
        List<TicketViews.IssueView> lineIssues = allIssues.stream()
                .filter(i -> i.getLineItem() != null && i.getLineItem().getId().equals(line.getId()))
                .map(this::toIssueView)
                .toList();

        StoreProduct product = line.getStoreProduct();
        TicketViews.ProductView productView = product == null ? null : new TicketViews.ProductView(
                product.getId(), product.getCanonicalName(), product.getDisplayName(),
                product.getNotes(), product.getPackageSize(), product.getPackageUnit(),
                product.getSoldBy() == null ? null : product.getSoldBy().name(),
                product.getCategory() == null ? null : product.getCategory().getId());

        // Solo se sugiere si el producto todavía no tiene tamaño: si ya lo tiene,
        // el dato bueno es el confirmado, no el deducido del texto.
        TicketViews.SizeSuggestion size = null;
        if (product == null || product.getPackageSize() == null) {
            size = sizeParser.parse(line.getRawDescription())
                    .map(s -> new TicketViews.SizeSuggestion(s.value(), s.unit(), s.dimension().name()))
                    .orElse(null);
        }

        return new TicketViews.LineView(
                line.getId(), line.getLineNo(), line.getRawRowText(), line.getRawDescription(),
                line.getQuantity(), line.getPrintedUnitPrice(), line.getLineTotal(),
                line.getTaxLetter(),
                Optional.ofNullable(line.getSoldBy()).map(Enum::name).orElse(null),
                line.isPromo(), line.getWeightValue(), line.getWeightUnit(),
                productView,
                Optional.ofNullable(line.getMatchMethod()).map(Enum::name).orElse(null),
                line.getMatchConfidence(), size, lineIssues);
    }

    private TicketViews.IssueView toIssueView(ValidationIssue issue) {
        return new TicketViews.IssueView(
                issue.getId(), issue.getCheckCode().name(), issue.getSeverity().name(),
                issue.getMessage(), issue.getExpected(), issue.getActual(),
                issue.getLineItem() == null ? null : issue.getLineItem().getId());
    }
}
