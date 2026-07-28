package com.juanluidos.ticketing.domain;

/**
 * Ciclo de vida del ticket.
 *
 * <p>La extracción es asíncrona: la subida responde en {@link #UPLOADED} y un
 * worker mueve el estado. En la GTX 1070 de producción un ticket denso puede
 * tardar minutos, así que nunca puede correr dentro de la petición HTTP.
 */
public enum TicketStatus {

    /** Imagen guardada, aún sin procesar. */
    UPLOADED,

    /** Encolado o en curso en el VLM. */
    EXTRACTING,

    /** JSON extraído y comprobaciones evaluadas; pendiente de revisión humana. */
    EXTRACTED,

    /** Confirmado por una persona. Solo desde aquí se generan PriceObservation. */
    VALIDATED,

    /** Falló la extracción. La imagen se conserva, se puede reintentar. */
    EXTRACTION_ERROR
}
