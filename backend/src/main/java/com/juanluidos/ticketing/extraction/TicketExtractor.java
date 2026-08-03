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
    private final ImagePreprocessor preprocessor;
    private final ObjectMapper objectMapper;

    public TicketExtractor(OllamaVisionClient client,
                           ExtractionPromptBuilder promptBuilder,
                           ExtractionSchemaProvider schemas,
                           ImagePreprocessor preprocessor,
                           ObjectMapper objectMapper) {
        this.client = client;
        this.promptBuilder = promptBuilder;
        this.schemas = schemas;
        this.preprocessor = preprocessor;
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
        // La original se conserva en disco; al modelo va una copia reducida, que
        // es lo que evita agotar la VRAM de la tarjeta de producción.
        String rawResponse = client.chatWithImage(
                prompt, preprocessor.prepare(image), schemas.asJsonNode());

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
            return objectMapper.readTree(isolateJsonObject(stripCodeFence(rawResponse)));
        } catch (JsonProcessingException e) {
            throw new ExtractionException(
                    "La respuesta del modelo no es JSON. Empieza por: " + head(rawResponse)
                            + " ... y acaba por: " + tail(rawResponse), e);
        }
    }

    /**
     * Se queda con el objeto JSON aunque venga precedido de texto.
     *
     * <p>Con {@code think: true} el razonamiento va aparte y esto no hace falta,
     * pero el comportamiento depende de que el modelo emita bien sus marcas de
     * razonamiento. Si no lo hace, la prosa aparece delante del JSON dentro de
     * {@code content} y perder la extracción entera por eso sería absurdo,
     * habiendo pagado ya los minutos de GPU.
     */
    private String isolateJsonObject(String raw) {
        if (raw.startsWith("{")) {
            return raw;
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return raw;
        }
        log.warn("La respuesta traía texto alrededor del JSON; se recorta a las llaves.");
        return raw.substring(start, end + 1);
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

    private String head(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        return trimmed.length() <= 300 ? trimmed : trimmed.substring(0, 300);
    }

    /** El final importa: dice si el JSON llegó a escribirse detrás de la prosa. */
    private String tail(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        return trimmed.length() <= 300 ? "" : trimmed.substring(trimmed.length() - 300);
    }

    public record Result(ExtractedTicket ticket, String rawJson) {
    }
}
