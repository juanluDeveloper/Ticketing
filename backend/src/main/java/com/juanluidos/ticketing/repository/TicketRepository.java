package com.juanluidos.ticketing.repository;

import com.juanluidos.ticketing.domain.Ticket;
import com.juanluidos.ticketing.domain.TicketStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface TicketRepository extends JpaRepository<Ticket, Long> {

    /** Deduplicación primaria: el número impreso del recibo (§4 de los hallazgos). */
    Optional<Ticket> findByStoreIdAndReceiptNumber(Long storeId, String receiptNumber);

    /** Respaldo cuando el número no se ha podido leer. */
    List<Ticket> findByStoreIdAndPurchasedAtAndTotal(Long storeId, LocalDateTime purchasedAt, BigDecimal total);

    /** Señal secundaria: la misma foto, byte a byte. Una refoto del mismo ticket no cae aquí. */
    List<Ticket> findByImageSha256(String imageSha256);

    List<Ticket> findByStatusOrderByCreatedAtAsc(TicketStatus status);

    List<Ticket> findByUserIdOrderByPurchasedAtDesc(Long userId);
}
