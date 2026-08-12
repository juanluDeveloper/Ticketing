package com.juanluidos.ticketing.ingest;

import com.juanluidos.ticketing.domain.CheckCode;
import com.juanluidos.ticketing.domain.Ticket;
import com.juanluidos.ticketing.domain.TicketStatus;
import com.juanluidos.ticketing.extraction.ExtractedTicket;
import com.juanluidos.ticketing.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Persistencia completa del caso Cash Fresh: 67,12 - 3,52 = 63,60. */
@SpringBootTest
@Transactional
class GeneralDiscountPersistenceTest {

    @Autowired
    private TicketExtractionStore extractionStore;

    @Autowired
    private TicketRepository tickets;

    @Autowired
    private TicketGeneralDiscountRepository discounts;

    @Autowired
    private TicketCheckResultRepository checks;

    @Autowired
    private AppUserRepository users;

    @Autowired
    private StoreRepository stores;

    @Test
    void keepsTheVoucherOutsideProductLinesAndStoresTheAmountPaid() {
        var cashFresh = stores.findByCode("CASH_FRESH").orElseThrow();
        Ticket ticket = new Ticket();
        ticket.setUser(users.findByUsername("juanluidos").orElseThrow());
        ticket.setStore(cashFresh);
        ticket.setStatus(TicketStatus.UPLOADED);
        ticket.setImagePath("no-existe/" + UUID.randomUUID() + ".png");
        ticket.setImageSha256(UUID.randomUUID().toString().replace("-", "").repeat(2).substring(0, 64));
        Long ticketId = tickets.saveAndFlush(ticket).getId();

        var line = new ExtractedTicket.ExtractedLineItem(
                "1 COMPRA 67,12 B", "COMPRA", BigDecimal.ONE, "unit", null,
                new BigDecimal("67.12"), "ud", new BigDecimal("67.12"), "B", false, null);
        var totals = new ExtractedTicket.ExtractedTotals(
                new BigDecimal("67.12"),
                List.of(new ExtractedTicket.ExtractedGeneralDiscount(
                        "VALES CLIENTES", new BigDecimal("3.52"))),
                new BigDecimal("63.60"),
                List.of());

        extractionStore.saveSuccess(ticketId, new ExtractedTicket(
                        new ExtractedTicket.ExtractedStore("Cash Fresh", "B41544503", null),
                        "2026-06-24T21:00:01", "TEST-" + UUID.randomUUID(),
                        "EUR", ",", null, List.of(line), totals),
                "{}", "test", cashFresh);

        Ticket saved = tickets.findById(ticketId).orElseThrow();
        assertThat(saved.getTotal()).isEqualByComparingTo("67.12");
        assertThat(saved.getAmountPaid()).isEqualByComparingTo("63.60");
        assertThat(discounts.findByTicketIdOrderByPositionAsc(ticketId))
                .singleElement()
                .satisfies(discount -> {
                    assertThat(discount.getDescription()).isEqualTo("VALES CLIENTES");
                    assertThat(discount.getAmount()).isEqualByComparingTo("3.52");
                });
        assertThat(checks.findByTicketId(ticketId))
                .anySatisfy(check -> {
                    assertThat(check.getCheckCode()).isEqualTo(CheckCode.C6);
                    assertThat(check.getPassed()).isTrue();
                });
    }
}
