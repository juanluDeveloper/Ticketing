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
            return call(buildBody(prompt, base64, format));
        } catch (RestClientResponseException e) {
            throw new ExtractionException(
                    "Ollama respondió " + e.getStatusCode() + ": " + e.getResponseBodyAsString(), e);
        } catch (ExtractionException e) {
            throw e;
        } catch (Exception e) {
            throw new ExtractionException(
                    "No se pudo llamar a Ollama en " + config.baseUrl(), e);
        }
    }

    private Map<String, Object> buildBody(String prompt, String base64Image, JsonNode format) {
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
        // Medido contra qwen3-vl:8b en Ollama 0.32.5 con las tres fotos reales:
        //   sin el campo / think=true -> el modelo razona en voz alta antes del
        //       JSON. En el ticket corto sobra presupuesto y sale bien, pero en
        //       Mercadona y Cash Fresh el razonamiento se come el contexto
        //       entero y la respuesta termina a media frase, sin JSON ninguno.
        //   think=false -> el modelo NO razona y emite el JSON directamente.
        //       Ollama lo etiqueta mal (sale por "thinking" y "content" llega
        //       vacío), pero el JSON está entero, que es lo que importa. De eso
        //       se encarga el respaldo de call().
        // Es decir: think=false no se pide para limpiar la salida, se pide para
        // no gastar el contexto en razonamiento que no aporta.
        body.put("think", false);
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

        if (response == null || response.message() == null) {
            throw new ExtractionException("Ollama devolvió una respuesta sin mensaje");
        }

        String content = response.message().content();
        if (content != null && !content.isBlank()) {
            return content;
        }

        // Red de seguridad para la rareza de arriba: si alguna combinación de
        // modelo y versión vuelve a mandar el JSON por "thinking", se aprovecha
        // en vez de perder la extracción.
        String thinking = response.message().thinking();
        if (thinking != null && !thinking.isBlank()) {
            log.warn("Ollama devolvió 'content' vacío y el JSON en 'thinking'; se usa 'thinking'.");
            return thinking;
        }

        throw new ExtractionException("Ollama devolvió una respuesta sin contenido");
    }

    record OllamaChatResponse(Message message, String model, Boolean done) {
        record Message(String role, String content, String thinking) {
        }
    }
}
