package com.juanluidos.ticketing.api;

import com.juanluidos.ticketing.extraction.ExtractionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.NoSuchElementException;

/**
 * Los mensajes de rechazo son parte del producto, no ruido de servidor.
 *
 * <p>Sin esto, cosas como "«DET MARSE FLOTA 100D» se mide en UNIT y el grupo
 * compara en VOLUME" o "no se puede validar: quedan 2 comprobaciones en rojo"
 * salían como un 500 genérico. El usuario veía "Error 500" y no tenía forma de
 * saber qué arreglar, que es justo lo contrario de lo que persigue esta
 * herramienta.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    public record ApiError(String message) {
    }

    /** Petición mal formada o incoherente: el usuario puede corregirla. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(new ApiError(e.getMessage()));
    }

    /** El estado actual no permite la operación; tampoco es un fallo del servidor. */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> conflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(e.getMessage()));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ApiError> notFound(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("No existe el recurso pedido"));
    }

    @ExceptionHandler(ExtractionException.class)
    public ResponseEntity<ApiError> extraction(ExtractionException e) {
        log.warn("Fallo de extracción servido por API", e);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new ApiError(e.getMessage()));
    }
}
