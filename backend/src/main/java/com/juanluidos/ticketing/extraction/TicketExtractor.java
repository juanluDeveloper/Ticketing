package com.juanluidos.ticketing.extraction;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.juanluidos.ticketing.domain.Store;
import com.networknt.schema.ValidationMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Foto + súper detectado → JSON del Anexo B validado.
 *
 * <p>No toca base de datos ni empareja productos: eso ocurre después, en la
 * validación. Aquí solo se garantiza que lo que devolvió el modelo tiene la
 * forma acordada.
 */
@Service
public class TicketExtractor {

    private static final Logger log = LoggerFactory.getLogger(TicketExtractor.class);

    private final OllamaVisionClient client;
    private final ExtractionPromptBuilder promptBuilder;
    private final ExtractionSchemaProvider schemas;
    private final ObjectMapper objectMapper;

    public TicketExtractor(OllamaVisionClient client,
                           ExtractionPromptBuilder promptBuilder,
                           ExtractionSchemaProvider schemas,
                           ObjectMapper objectMapper) {
        this.client = client;
        this.promptBuilder = promptBuilder;
        this.schemas = schemas;
        this.objectMapper = objectMapper;
    }

    /**
     * @param storeHint súper detectado por el NIF/CIF de la cabecera; determina
     *                  qué reglas de layout se le explican al modelo
     * @return el ticket extraído y el JSON crudo, que se guarda para poder
     *         auditar y reprocesar sin volver a pasar por la GPU
     */
    public Result extract(byte[] image, Store storeHint) {
        String prompt = promptBuilder.build(storeHint);
        String rawResponse = client.chatWithImage(prompt, image, schemas.asJsonNode());

        JsonNode parsed = parse(rawResponse);
        verifyAgainstSchema(parsed);

        try {
            return new Result(objectMapper.treeToValue(parsed, ExtractedTicket.class), rawResponse);
        } catch (JsonProcessingException e) {
            throw new ExtractionException("El JSON cumple el esquema pero no se pudo mapear", e);
        }
    }

    private JsonNode parse(String rawResponse) {
        try {
            return objectMapper.readTree(stripCodeFence(rawResponse));
        } catch (JsonProcessingException e) {
            throw new ExtractionException(
                    "La respuesta del modelo no es JSON: " + preview(rawResponse), e);
        }
    }

    /**
     * Revalidación en el backend. La decodificación restringida de Ollama fuerza
     * la forma, no la veracidad, y no cubre un reintento que se haya ido sin
     * {@code format}. Es barato y evita meter basura estructurada en la base.
     */
    private void verifyAgainstSchema(JsonNode parsed) {
        Set<ValidationMessage> errors = schemas.validate(parsed);
        if (!errors.isEmpty()) {
            String detail = errors.stream()
                    .map(ValidationMessage::getMessage)
                    .limit(10)
                    .collect(Collectors.joining("; "));
            throw new ExtractionException("El JSON no cumple el esquema del Anexo B: " + detail);
        }
    }

    /**
     * Red de seguridad: con {@code format} el modelo no debería envolver el JSON
     * en un bloque de código, pero si lo hace no merece la pena perder la
     * extracción entera por tres acentos graves.
     */
    private String stripCodeFence(String raw) {
        String trimmed = raw.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int firstNewline = trimmed.indexOf('\n');
        int closing = trimmed.lastIndexOf("```");
        if (firstNewline < 0 || closing <= firstNewline) {
            return trimmed;
        }
        log.debug("La respuesta venía envuelta en un bloque de código; se retira.");
        return trimmed.substring(firstNewline + 1, closing).trim();
    }

    private String preview(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        return trimmed.length() <= 300 ? trimmed : trimmed.substring(0, 300) + "…";
    }

    public record Result(ExtractedTicket ticket, String rawJson) {
    }
}
