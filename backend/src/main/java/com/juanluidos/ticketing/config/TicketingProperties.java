package com.juanluidos.ticketing.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

@ConfigurationProperties(prefix = "ticketing")
public record TicketingProperties(
        String seedUserPassword,
        Storage storage,
        Ollama ollama,
        Validation validation
) {

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
