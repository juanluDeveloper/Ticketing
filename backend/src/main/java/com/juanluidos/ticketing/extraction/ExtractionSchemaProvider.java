package com.juanluidos.ticketing.extraction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Set;

/**
 * El esquema del Anexo B, usado para dos cosas distintas.
 *
 * <p>Se le manda a Ollama como {@code format} para que la decodificación
 * restringida no pueda producir otra forma, y se usa aquí para revalidar la
 * respuesta. Lo segundo no sobra: la decodificación restringida garantiza la
 * forma, no la veracidad, y tampoco cubre el caso de que la llamada haya ido por
 * un camino sin {@code format} (un reintento, otro backend, un modelo que ignore
 * el campo).
 */
@Component
public class ExtractionSchemaProvider {

    private static final String SCHEMA_PATH = "extraction/anexo-b.schema.json";

    private final JsonNode schemaNode;
    private final JsonSchema compiledSchema;

    public ExtractionSchemaProvider(ObjectMapper objectMapper) {
        try (InputStream in = new ClassPathResource(SCHEMA_PATH).getInputStream()) {
            this.schemaNode = objectMapper.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer " + SCHEMA_PATH, e);
        }
        this.compiledSchema = JsonSchemaFactory
                .getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(this.schemaNode);
    }

    /** Lo que viaja en el campo {@code format} de la petición a Ollama. */
    public JsonNode asJsonNode() {
        return schemaNode;
    }

    /** Vacío si el JSON cumple el esquema. */
    public Set<ValidationMessage> validate(JsonNode instance) {
        return compiledSchema.validate(instance);
    }
}
