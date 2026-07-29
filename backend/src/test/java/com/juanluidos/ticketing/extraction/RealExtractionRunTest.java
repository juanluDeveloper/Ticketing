package com.juanluidos.ticketing.extraction;

import com.juanluidos.ticketing.domain.Store;
import com.juanluidos.ticketing.domain.StoreTaxLetter;
import com.juanluidos.ticketing.repository.StoreRepository;
import com.juanluidos.ticketing.repository.StoreTaxLetterRepository;
import com.juanluidos.ticketing.validation.CheckReport;
import com.juanluidos.ticketing.validation.ExtractionCheckEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Corrida real contra las tres fotos, con Ollama y el modelo de verdad.
 *
 * <p>No corre en la suite normal: gasta GPU y depende de que Ollama esté
 * levantado con el modelo cargado. Se lanza a mano:
 *
 * <pre>mvn test -Dtest=RealExtractionRunTest -Dticketing.realRun=true</pre>
 */
@SpringBootTest
@EnabledIfSystemProperty(named = "ticketing.realRun", matches = "true")
class RealExtractionRunTest {

    private static final Path PHOTOS = Path.of("..", "Ejemplo tickets cada super");

    @Autowired
    private TicketExtractor extractor;

    @Autowired
    private ExtractionCheckEngine checkEngine;

    @Autowired
    private StoreRepository stores;

    @Autowired
    private StoreTaxLetterRepository taxLetters;

    @Test
    void mercadona() throws Exception {
        run("mercadona.jpeg", "MERCADONA", new BigDecimal("63.16"), 26);
    }

    @Test
    void cashFresh() throws Exception {
        run("cash fresh.jpeg", "CASH_FRESH", new BigDecimal("29.48"), 11);
    }

    @Test
    void xinya() throws Exception {
        run("chino.jpeg", "XINYA", new BigDecimal("9.90"), 2);
    }

    private void run(String fileName, String storeCode, BigDecimal expectedTotal, int expectedLines)
            throws Exception {
        Store store = stores.findByCode(storeCode).orElseThrow();
        List<StoreTaxLetter> letters = taxLetters.findByStoreId(store.getId());
        byte[] image = Files.readAllBytes(PHOTOS.resolve(fileName));

        long start = System.currentTimeMillis();
        TicketExtractor.Result result = extractor.extract(image, store);
        long millis = System.currentTimeMillis() - start;

        ExtractedTicket t = result.ticket();
        CheckReport report = checkEngine.evaluate(t, store, letters);

        System.out.println("\n================ " + storeCode + " (" + millis + " ms) ================");
        System.out.println("súper       : " + t.store().name() + "  nif=" + t.store().nif());
        System.out.println("fecha       : " + t.purchasedAt());
        System.out.println("nº recibo   : " + t.receiptNumber());
        System.out.println("moneda      : " + t.currency() + "   separador=" + t.decimalSeparator());
        System.out.println("artículos   : " + t.articleCount());
        System.out.println("total       : " + t.totals().total() + "   (esperado " + expectedTotal + ")");
        System.out.println("líneas      : " + t.lineItems().size() + "   (esperado " + expectedLines + ")");
        System.out.println("IVA         : " + t.totals().taxBreakdown());

        System.out.println("\n--- líneas ---");
        for (int i = 0; i < t.lineItems().size(); i++) {
            var l = t.lineItems().get(i);
            System.out.printf("%2d | %-24s | q=%-6s pu=%-7s tot=%-7s letra=%-4s sold=%-15s%n",
                    i + 1, truncate(l.rawDescription()), l.quantity(), l.unitPrice(),
                    l.lineTotal(), l.taxLetter(), l.soldBy());
            System.out.println("   raw: " + truncateLong(l.rawRowText()));
        }

        System.out.println("\n--- comprobaciones ---");
        for (var o : report.outcomes()) {
            String state = !o.applicable() ? "NO APLICA" : (Boolean.TRUE.equals(o.passed()) ? "PASA" : "FALLA");
            System.out.printf("%-3s %-10s cubre=%-3s  %s%n",
                    o.code(), state, o.linesCovered(), o.detail());
        }
        System.out.println("cobertura   : " + report.coveredLines() + "/" + report.lineCount()
                + " = " + report.coverageRatio());

        if (!report.findings().isEmpty()) {
            System.out.println("\n--- hallazgos ---");
            report.findings().forEach(f -> System.out.println("  [" + f.severity() + "] "
                    + f.code() + " línea=" + f.lineNo() + ": " + f.message()));
        }

        // Solo se afirma lo que se sabe seguro de la foto. La calidad de lectura
        // se juzga con lo impreso arriba, no con un assert.
        assertThat(t.totals().total()).isEqualByComparingTo(expectedTotal);
    }

    private String truncate(String s) {
        if (s == null) return "";
        return s.length() <= 24 ? s : s.substring(0, 23) + "…";
    }

    private String truncateLong(String s) {
        if (s == null) return "(null)";
        return s.length() <= 90 ? s : s.substring(0, 89) + "…";
    }
}
