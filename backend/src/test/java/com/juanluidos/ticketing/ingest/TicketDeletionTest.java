package com.juanluidos.ticketing.ingest;

import com.juanluidos.ticketing.domain.Ticket;
import com.juanluidos.ticketing.domain.TicketStatus;
import com.juanluidos.ticketing.repository.AppUserRepository;
import com.juanluidos.ticketing.repository.TicketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Borrar un ticket es lo que libera su número de recibo. Sin eso, una compra
 * fotografiada mal queda inservible para siempre: la deduplicación impide
 * volver a subirla y no hay forma de quitar la mala.
 */
@SpringBootTest
@Transactional
class TicketDeletionTest {

    @Autowired
    private TicketIngestService ingest;

    @Autowired
    private TicketRepository tickets;

    @Autowired
    private AppUserRepository users;

    @Autowired
    private com.juanluidos.ticketing.repository.StoreRepository stores;

    private Long userId;

    @BeforeEach
    void loadUser() {
        userId = users.findByUsername("juanluidos").orElseThrow().getId();
    }

    @Test
    void deletesATicketThatFailedExtraction() {
        Long id = insert(TicketStatus.EXTRACTION_ERROR, null);

        ingest.delete(id, false);

        assertThat(tickets.findById(id)).isEmpty();
    }

    @Test
    void deletesATicketPendingReview() {
        Long id = insert(TicketStatus.EXTRACTED, null);

        assertThatCode(() -> ingest.delete(id, false)).doesNotThrowAnyException();
    }

    /** El motivo de todo esto: recuperar el número para volver a subir la compra. */
    @Test
    void freesTheReceiptNumberForAFreshUpload() {
        String receipt = "TEST-" + UUID.randomUUID();
        Long storeId = stores.findByCode("MERCADONA").orElseThrow().getId();
        Long first = insert(TicketStatus.EXTRACTED, receipt);

        assertThat(tickets.findByStoreIdAndReceiptNumber(storeId, receipt)).isPresent();

        ingest.delete(first, false);
        tickets.flush();

        assertThat(tickets.findByStoreIdAndReceiptNumber(storeId, receipt)).isEmpty();
    }

    /** Borrar un validado se lleva su serie de precios: no puede ser un descuido. */
    @Test
    void refusesToDeleteAValidatedTicketWithoutConfirmation() {
        Long id = insert(TicketStatus.VALIDATED, null);

        assertThatThrownBy(() -> ingest.delete(id, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("histórico");
    }

    @Test
    void deletesAValidatedTicketWhenConfirmed() {
        Long id = insert(TicketStatus.VALIDATED, null);

        ingest.delete(id, true);

        assertThat(tickets.findById(id)).isEmpty();
    }

    /** Mientras el worker lo tiene entre manos, borrarlo es pedir una carrera. */
    @Test
    void refusesToDeleteWhileExtractionIsRunning() {
        Long id = insert(TicketStatus.EXTRACTING, null);

        assertThatThrownBy(() -> ingest.delete(id, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("extrayendo");
    }

    private Long insert(TicketStatus status, String receiptNumber) {
        Ticket ticket = new Ticket();
        ticket.setUser(users.findById(userId).orElseThrow());
        ticket.setStore(stores.findByCode("MERCADONA").orElseThrow());
        ticket.setStatus(status);
        ticket.setReceiptNumber(receiptNumber);
        ticket.setTotal(new BigDecimal("29.48"));
        // Ruta inventada a propósito: borrar un ticket cuya imagen ya no está
        // tiene que funcionar igual, no reventar.
        ticket.setImagePath("no-existe/" + UUID.randomUUID() + ".jpg");
        ticket.setImageSha256(UUID.randomUUID().toString().replace("-", "").repeat(2).substring(0, 64));
        return tickets.saveAndFlush(ticket).getId();
    }
}
