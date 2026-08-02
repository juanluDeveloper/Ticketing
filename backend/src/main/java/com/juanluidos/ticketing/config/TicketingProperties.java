package com.juanluidos.ticketing.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

@ConfigurationProperties(prefix = "ticketing")
public record TicketingProperties(
        String seedUserPassword,
        Storage storage,
        Ollama ollama,
        Validation validation,
        Cors cors
) {

    /**
     * Orígenes permitidos.
     *
     * <p>En producción el frontend y la API se sirven desde el mismo host a
     * través de nginx, así que en teoría no haría falta CORS. En la práctica sí:
     * los navegadores mandan cabecera {@code Origin} en los POST incluso cuando
     * son del mismo origen, y detrás de un proxy Spring no puede reconocerlos
     * como tales. Si el origen público no está en esta lista, subir una foto
     * falla con "Invalid CORS request" mientras el resto de la app funciona.
     */
    public record Cors(java.util.List<String> allowedOrigins) {
    }

    public record Storage(String imageDir) {
    }

    /**
     * Configuración del extractor.
     *
     * <p>{@code numCtx} está aquí y no en el cliente a propósito: dev (RTX 5070 Ti,
     * 16 GB) y producción (GTX 1070, 8 GB) tienen que correr con el mismo valor y
     * la misma cuantización, o la calidad de extracción que se mide en dev no
     * predice la de producción.
     */
    public record Ollama(String baseUrl, String model, int numCtx, int timeoutSeconds) {
    }

    /** Tolerancias de los checksums, en euros. */
    public record Validation(BigDecimal amountTolerance, BigDecimal weightAmountTolerance) {
    }
}
