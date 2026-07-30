package com.juanluidos.ticketing.ingest;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * El prompt pide {@code AAAA-MM-DDTHH:MM:SS} y el modelo casi siempre obedece,
 * pero se le escapa la variante con espacio o sin segundos. Perder la fecha del
 * ticket por eso sería absurdo, así que se aceptan las formas cercanas.
 */
final class ReceiptDateTimeParser {

    private static final List<DateTimeFormatter> FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));

    private ReceiptDateTimeParser() {
    }

    /** @return null si no se puede interpretar; la persona lo corrige en la validación */
    static LocalDateTime parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        for (DateTimeFormatter format : FORMATS) {
            try {
                return LocalDateTime.parse(trimmed, format);
            } catch (DateTimeParseException ignored) {
                // se prueba el siguiente
            }
        }
        return null;
    }
}
