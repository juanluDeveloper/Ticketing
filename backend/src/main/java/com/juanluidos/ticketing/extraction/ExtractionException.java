package com.juanluidos.ticketing.extraction;

/** Falla la extracción. La imagen se conserva, así que siempre se puede reintentar. */
public class ExtractionException extends RuntimeException {

    public ExtractionException(String message) {
        super(message);
    }

    public ExtractionException(String message, Throwable cause) {
        super(message, cause);
    }
}
