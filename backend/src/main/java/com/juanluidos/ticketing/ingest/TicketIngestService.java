package com.juanluidos.ticketing.ingest;

import com.juanluidos.ticketing.domain.Ticket;
import com.juanluidos.ticketing.domain.TicketStatus;
import com.juanluidos.ticketing.repository.AppUserRepository;
import com.juanluidos.ticketing.repository.TicketRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Subida de la foto. No es transaccional a propósito: si el trabajo asíncrono
 * arrancara antes del commit, no encontraría el ticket.
 */
@Service
public class TicketIngestService {

    private final TicketRepository tickets;
    private final AppUserRepository users;
    private final TicketImageStorage images;
    private final TicketExtractionJob job;

    public TicketIngestService(TicketRepository tickets, AppUserRepository users,
                               TicketImageStorage images, TicketExtractionJob job) {
        this.tickets = tickets;
        this.users = users;
        this.images = images;
        this.job = job;
    }

    public record Upload(Ticket ticket, List<Long> sameImageAs) {
    }

    public Upload upload(String username, byte[] image, String fileName, String storeCodeHint) {
        if (image == null || image.length == 0) {
            throw new IllegalArgumentException("La imagen está vacía");
        }
        String sha = images.sha256(image);

        // Solo informativo: detecta la misma foto byte a byte, no una segunda
        // foto del mismo ticket. Eso lo resuelve el número de recibo al extraer.
        List<Long> sameImage = tickets.findByImageSha256(sha).stream().map(Ticket::getId).toList();

        Ticket ticket = new Ticket();
        ticket.setUser(users.findByUsername(username).orElseThrow());
        ticket.setImagePath(images.store(image, fileName));
        ticket.setImageSha256(sha);
        ticket.setStatus(TicketStatus.UPLOADED);
        Ticket saved = tickets.save(ticket);

        job.run(saved.getId(), storeCodeHint);
        return new Upload(saved, sameImage);
    }

    /** Reextrae desde la imagen guardada, con el prompt y el modelo de ahora. */
    public void reextract(Long ticketId, String storeCodeHint) {
        Ticket ticket = tickets.findById(ticketId).orElseThrow();
        if (ticket.getStatus() == TicketStatus.EXTRACTING) {
            throw new IllegalStateException("El ticket #" + ticketId + " ya se está extrayendo");
        }
        job.run(ticket.getId(), storeCodeHint);
    }
}
