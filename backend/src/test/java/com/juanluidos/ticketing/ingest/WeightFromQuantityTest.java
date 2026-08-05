package com.juanluidos.ticketing.ingest;

import com.juanluidos.ticketing.domain.LineItem;
import com.juanluidos.ticketing.domain.SoldBy;
import com.juanluidos.ticketing.domain.Ticket;
import com.juanluidos.ticketing.domain.TicketStatus;
import com.juanluidos.ticketing.extraction.ExtractedTicket;
import com.juanluidos.ticketing.extraction.ExtractedTicket.ExtractedLineItem;
import com.juanluidos.ticketing.extraction.ExtractedTicket.ExtractedTotals;
import com.juanluidos.ticketing.extraction.ExtractedTicket.ExtractedWeight;
import com.juanluidos.ticketing.repository.AppUserRepository;
import com.juanluidos.ticketing.repository.LineItemRepository;
import com.juanluidos.ticketing.repository.StoreRepository;
import com.juanluidos.ticketing.repository.TicketRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cash Fresh imprime los productos a peso con el mismo formato que las
 * cantidades — "QUESO CABRA PAYOYA 0,260x 31,95 8,31" —, así que el modelo mete
 * el peso en la casilla de cantidad. Es una lectura razonable del papel, pero
 * deja la compra sin precio por kilo y fuera de la comparación.
 *
 * <p>Como esto ADIVINA, lo que más importa es que no se dispare cuando no debe.
 */
@SpringBootTest
@Transactional
class WeightFromQuantityTest {

    @Autowired
    private TicketExtractionStore store;

    @Autowired
    private TicketRepository tickets;

    @Autowired
    private LineItemRepository lineItems;

    @Autowired
    private AppUserRepository users;

    @Autowired
    private StoreRepository stores;

    /** El caso real: 0,26 x 31,95 EUR/kg = 8,31. Ese 0,26 son kilos. */
    @Test
    void movesTheWeightOutOfTheQuantityWhenTheArithmeticProvesIt() {
        LineItem line = extractOneLine(
                weightLine("0.26", "31.95", "kg", "8.31", null));

        assertThat(line.getWeightValue()).isEqualByComparingTo("0.26");
        assertThat(line.getWeightUnit()).isEqualTo("kg");
        // A peso se compró UNA pieza, que pesa eso.
        assertThat(line.getQuantity()).isEqualByComparingTo("1");
    }

    /**
     * Lo que devuelve el modelo de verdad es la unidad tal como está impresa:
     * "€/kg", no "kg". Comparar contra "kg" a secas hacía que la recuperación
     * no se disparara nunca, y de paso la interfaz pintaba "€/€/kg".
     */
    @Test
    void understandsTheUnitAsThePrinterWritesIt() {
        LineItem line = extractOneLine(
                weightLine("0.26", "31.95", "€/kg", "8.31", null));

        assertThat(line.getPrintedUnitPriceUnit()).isEqualTo("kg");
        assertThat(line.getWeightValue()).isEqualByComparingTo("0.26");
        assertThat(line.getWeightUnit()).isEqualTo("kg");
        assertThat(line.getQuantity()).isEqualByComparingTo("1");
    }

    @Test
    void normalizesTheUnitOfAWeightTheModelDidRead() {
        LineItem line = extractOneLine(
                weightLine("1", "3.05", "EUR/kg", "4.25",
                        new ExtractedWeight(new BigDecimal("1.394"), "Kg")));

        assertThat(line.getWeightUnit()).isEqualTo("kg");
        assertThat(line.getPrintedUnitPriceUnit()).isEqualTo("kg");
    }

    /** Si la cuenta no sale, ese número no es un peso y no se toca nada. */
    @Test
    void leavesItAloneWhenTheArithmeticDoesNotAddUp() {
        LineItem line = extractOneLine(
                weightLine("2", "31.95", "kg", "8.31", null));

        assertThat(line.getWeightValue()).isNull();
        assertThat(line.getQuantity()).isEqualByComparingTo("2");
    }

    /** Precio por unidad, no por peso: dos unidades son dos unidades. */
    @Test
    void leavesItAloneWhenThePriceIsNotPerWeight() {
        LineItem line = extractOneLine(
                weightLine("2", "1.45", "ud", "2.90", null));

        assertThat(line.getWeightValue()).isNull();
        assertThat(line.getQuantity()).isEqualByComparingTo("2");
    }

    /** Si el modelo ya leyó el peso, no se pisa. */
    @Test
    void leavesAnAlreadyReadWeightUntouched() {
        LineItem line = extractOneLine(
                weightLine("1", "3.05", "kg", "4.25", new ExtractedWeight(new BigDecimal("1.394"), "kg")));

        assertThat(line.getWeightValue()).isEqualByComparingTo("1.394");
        assertThat(line.getQuantity()).isEqualByComparingTo("1");
    }

    /** Una línea normal envasada no se convierte en peso por accidente. */
    @Test
    void leavesPackagedLinesAlone() {
        ExtractedLineItem packaged = new ExtractedLineItem(
                "2 LECHE 1,15 2,30", "LECHE", new BigDecimal("2"), "unit", null,
                new BigDecimal("1.15"), "ud", new BigDecimal("2.30"), null, false, null);

        LineItem line = extractOneLine(packaged);

        assertThat(line.getSoldBy()).isEqualTo(SoldBy.PACKAGE);
        assertThat(line.getWeightValue()).isNull();
        assertThat(line.getQuantity()).isEqualByComparingTo("2");
    }

    // ------------------------------------------------------------------

    private ExtractedLineItem weightLine(String quantity, String unitPrice, String unit,
                                         String total, ExtractedWeight weight) {
        return new ExtractedLineItem(
                "QUESO CABRA PAYOYA  " + quantity + "x " + unitPrice + "  " + total,
                "QUESO CABRA PAYOYA", new BigDecimal(quantity), "weight", weight,
                new BigDecimal(unitPrice), unit, new BigDecimal(total), "A", false, null);
    }

    private LineItem extractOneLine(ExtractedLineItem source) {
        Ticket ticket = new Ticket();
        ticket.setUser(users.findByUsername("juanluidos").orElseThrow());
        ticket.setStore(stores.findByCode("CASH_FRESH").orElseThrow());
        ticket.setStatus(TicketStatus.UPLOADED);
        ticket.setImagePath("no-existe/" + UUID.randomUUID() + ".jpg");
        ticket.setImageSha256(UUID.randomUUID().toString().replace("-", "").repeat(2).substring(0, 64));
        Long id = tickets.saveAndFlush(ticket).getId();

        store.saveSuccess(id, new ExtractedTicket(
                        new ExtractedTicket.ExtractedStore("Cash Fresh", "B41544503", null),
                        "2026-05-16T21:23:38", "TEST-" + UUID.randomUUID(), "EUR", ",", null,
                        List.of(source),
                        new ExtractedTotals(source.lineTotal(), List.of())),
                "{}", "test", stores.findByCode("CASH_FRESH").orElseThrow());

        return lineItems.findByTicketIdOrderByLineNoAsc(id).getFirst();
    }
}
