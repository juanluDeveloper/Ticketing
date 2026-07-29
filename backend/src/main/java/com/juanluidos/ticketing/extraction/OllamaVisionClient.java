package com.juanluidos.ticketing.extraction;

import com.fasterxml.jackson.databind.JsonNode;
import com.juanluidos.ticketing.config.TicketingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cliente de la API nativa de Ollama ({@code POST /api/chat}).
 *
 * <p>Nativa y no el shim compatible con OpenAI a propósito: la salida
 * estructurada por JSON Schema va en el campo {@code format} de esta API y es
 * ahí donde el soporte es fiable.
 */
@Component
public class OllamaVisionClient {

    private static final Logger log = LoggerFactory.getLogger(OllamaVisionClient.class);

    private final RestClient restClient;
    private final TicketingProperties.Ollama config;

    public OllamaVisionClient(TicketingProperties properties) {
        this.config = properties.ollama();

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        // Un ticket denso en la GTX 1070 puede tardar minutos: el timeout de
        // lectura tiene que ser generoso o se corta la extracción a medias.
        factory.setReadTimeout(Duration.ofSeconds(config.timeoutSeconds()));

        this.restClient = RestClient.builder()
                .baseUrl(config.baseUrl())
                .requestFactory(factory)
                .build();
    }

    /**
     * Manda imagen y prompt, y devuelve el contenido del mensaje: el JSON en
     * crudo, tal cual lo escribió el modelo.
     */
    public String chatWithImage(String prompt, byte[] image, JsonNode format) {
        String base64 = Base64.getEncoder().encodeToString(image);

        try {
            return call(buildBody(prompt, base64, format, true));
        } catch (RestClientResponseException e) {
            // No todos los modelos declaran capacidad de razonamiento, y a los
            // que no la tienen Ollama les rechaza el campo "think". Se reintenta
            // sin él en vez de dar la extracción por fallida.
            if (mentionsThinkingUnsupported(e)) {
                log.debug("El modelo {} no acepta 'think'; reintento sin el campo.", config.model());
                return call(buildBody(prompt, base64, format, false));
            }
            throw new ExtractionException(
                    "Ollama respondió " + e.getStatusCode() + ": " + e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            throw new ExtractionException(
                    "No se pudo llamar a Ollama en " + config.baseUrl(), e);
        }
    }

    private Map<String, Object> buildBody(String prompt, String base64Image,
                                          JsonNode format, boolean disableThinking) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("content", prompt);
        message.put("images", List.of(base64Image));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.model());
        body.put("messages", List.of(message));
        // Fuerza la forma del Anexo B: el modelo no puede devolver campos de más
        // ni de menos, ni renombrarlos con tildes o espacios.
        body.put("format", format);
        body.put("stream", false);
        if (disableThinking) {
            // Sin esto, los modelos híbridos sacan su razonamiento en voz alta
            // antes del JSON.
            body.put("think", false);
        }
        body.put("options", Map.of(
                // Fijado en configuración para que dev y producción se comporten
                // igual; si difieren, medir la calidad en dev no dice nada.
                "num_ctx", config.numCtx(),
                // Extraer un ticket no es una tarea creativa.
                "temperature", 0));

        return body;
    }

    private String call(Map<String, Object> body) {
        OllamaChatResponse response = restClient.post()
                .uri("/api/chat")
                .body(body)
                .retrieve()
                .body(OllamaChatResponse.class);

        if (response == null || response.message() == null || response.message().content() == null) {
            throw new ExtractionException("Ollama devolvió una respuesta sin contenido");
        }
        return response.message().content();
    }

    private boolean mentionsThinkingUnsupported(RestClientResponseException e) {
        String body = e.getResponseBodyAsString();
        return body != null && body.toLowerCase().contains("think");
    }

    record OllamaChatResponse(Message message, String model, Boolean done) {
        record Message(String role, String content) {
        }
    }
}
