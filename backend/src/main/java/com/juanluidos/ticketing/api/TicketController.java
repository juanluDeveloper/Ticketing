package com.juanluidos.ticketing.api;

import com.juanluidos.ticketing.domain.Ticket;
import com.juanluidos.ticketing.ingest.TicketImageStorage;
import com.juanluidos.ticketing.ingest.TicketIngestService;
import com.juanluidos.ticketing.repository.AppUserRepository;
import com.juanluidos.ticketing.repository.TicketRepository;
import com.juanluidos.ticketing.validation.TicketValidationService;
import com.juanluidos.ticketing.validation.ValidationRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.security.Principal;
import java.util.List;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@RestController
@RequestMapping("/api/tickets")
public class TicketController {

    private final TicketIngestService ingest;
    private final TicketValidationService validation;
    private final TicketRepository tickets;
    private final AppUserRepository users;
    private final TicketViewAssembler assembler;
    private final TicketImageStorage images;

    public TicketController(TicketIngestService ingest, TicketValidationService validation,
                            TicketRepository tickets, AppUserRepository users,
                            TicketViewAssembler assembler, TicketImageStorage images) {
        this.ingest = ingest;
        this.validation = validation;
        this.tickets = tickets;
        this.users = users;
        this.assembler = assembler;
        this.images = images;
    }

    /**
     * Sube la foto y responde en cuanto está en disco. La extracción va por
     * detrás: en la GTX 1070 tarda minutos y no cabe en una petición HTTP. El
     * cliente sondea {@link #detail} hasta que el estado deja de ser EXTRACTING.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public TicketViews.UploadResponse upload(@RequestParam("file") MultipartFile file,
                                             @RequestParam(required = false) String storeCode,
                                             Principal principal) throws IOException {
        TicketIngestService.Upload upload =
                ingest.upload(principal.getName(), file.getBytes(), file.getOriginalFilename(), storeCode);
        return new TicketViews.UploadResponse(
                upload.ticket().getId(), upload.ticket().getStatus().name(), upload.sameImageAs());
    }

    @GetMapping
    public List<TicketViews.TicketSummary> list(Principal principal) {
        return assembler.summaries(userId(principal));
    }

    @GetMapping("/{id}")
    public TicketViews.TicketDetail detail(@PathVariable Long id) {
        find(id);
        return assembler.detail(id);
    }

    /** La foto, para poder cotejar la fila transcrita con el papel. */
    @GetMapping("/{id}/image")
    public ResponseEntity<byte[]> image(@PathVariable Long id) {
        Ticket ticket = find(id);
        byte[] bytes = images.read(ticket.getImagePath());
        return ResponseEntity.ok()
                .contentType(ticket.getImagePath().endsWith(".png") ? MediaType.IMAGE_PNG : MediaType.IMAGE_JPEG)
                .body(bytes);
    }

    /** Reextrae desde la imagen guardada, con el prompt y el modelo actuales. */
    @PostMapping("/{id}/reextract")
    public TicketViews.TicketSummary reextract(@PathVariable Long id,
                                              @RequestParam(required = false) String storeCode) {
        ingest.reextract(id, storeCode);
        return assembler.summary(id);
    }

    /**
     * Guarda las correcciones y, con {@code confirm}, cierra el ticket. Vuelve a
     * pasar las comprobaciones, así que los semáforos reflejan lo corregido y no
     * lo que dijo la extracción.
     */
    @PostMapping("/{id}/validate")
    public TicketViews.TicketDetail validate(@PathVariable Long id,
                                             @RequestBody ValidationRequest request,
                                             Principal principal) {
        validation.apply(id, request, principal.getName());
        return assembler.detail(id);
    }

    private Ticket find(Long id) {
        return tickets.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "No existe el ticket " + id));
    }

    private Long userId(Principal principal) {
        return users.findByUsername(principal.getName())
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Usuario desconocido"))
                .getId();
    }
}
