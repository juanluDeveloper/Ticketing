package com.juanluidos.ticketing.ingest;

import com.juanluidos.ticketing.config.TicketingProperties;
import com.juanluidos.ticketing.extraction.TicketExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * La extracción, fuera de la petición HTTP.
 *
 * <p>En la GTX 1070 un ticket denso puede tardar minutos, así que la subida
 * responde en cuanto la imagen está en disco y esto va por detrás. La clase no
 * es transaccional a propósito: la llamada al modelo NO puede ocurrir con una
 * transacción abierta, y los tramos que tocan base están en
 * {@link TicketExtractionStore}.
 */
@Component
public class TicketExtractionJob {

    private static final Logger log = LoggerFactory.getLogger(TicketExtractionJob.class);

    private final TicketExtractionStore store;
    private final TicketImageStorage images;
    private final TicketExtractor extractor;
    private final TicketingProperties properties;

    public TicketExtractionJob(TicketExtractionStore store, TicketImageStorage images,
                               TicketExtractor extractor, TicketingProperties properties) {
        this.store = store;
        this.images = images;
        this.extractor = extractor;
        this.properties = properties;
    }

    @Async("extractionExecutor")
    public void run(Long ticketId, String storeCodeHint) {
        log.info("Extrayendo ticket #{}", ticketId);
        TicketExtractionStore.ExtractionInput input;
        try {
            input = store.begin(ticketId, storeCodeHint);
        } catch (RuntimeException e) {
            log.error("No se pudo marcar el ticket #{} como en extracción", ticketId, e);
            return;
        }

        try {
            byte[] image = images.read(input.imagePath());
            TicketExtractor.Result result = extractor.extract(image, input.store());
            var reextractWith = store.saveSuccess(ticketId, result.ticket(), result.rawJson(),
                    properties.ollama().model(), input.store());
            log.info("Ticket #{} extraído: {} líneas",
                    ticketId, result.ticket().lineItems() == null ? 0 : result.ticket().lineItems().size());

            // La primera pasada va con prompt genérico porque el súper aún no se
            // conoce. Ya leída la cabecera, se repite una vez con las reglas de
            // layout de ese súper, que es lo que habilita C3 y C4.
            // Autoinvocación: no pasa por el proxy de @Async, así que la segunda
            // pasada corre en este mismo hilo. Es lo que interesa — la GPU
            // atiende de una en una y no tiene sentido devolverla a una cola de
            // un solo hilo para esperarse a sí misma.
            reextractWith.ifPresent(code -> {
                log.info("Ticket #{} es de {}: reextrayendo con sus reglas de layout", ticketId, code);
                run(ticketId, code);
            });
        } catch (Exception e) {
            log.error("Falló la extracción del ticket #{}", ticketId, e);
            // La imagen se conserva, así que reintentar es siempre posible.
            store.saveFailure(ticketId, e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }
}
