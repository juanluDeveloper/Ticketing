package com.juanluidos.ticketing.ingest;

import com.juanluidos.ticketing.config.TicketingProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Guarda la imagen original del ticket en disco. Se conserva siempre: es lo que
 * permite reextraer cuando mejore el prompt o el modelo, y auditar una línea
 * dudosa meses después.
 */
@Component
public class TicketImageStorage {

    private final Path root;

    public TicketImageStorage(TicketingProperties properties) {
        this.root = Path.of(properties.storage().imageDir()).toAbsolutePath().normalize();
    }

    /** @return ruta relativa a la raíz de almacenamiento, que es lo que va en la base */
    public String store(byte[] image, String originalFileName) {
        // Por día, para que el directorio no acabe con miles de ficheros planos.
        Path dir = root.resolve(LocalDate.now().toString());
        String name = UUID.randomUUID() + extensionOf(originalFileName);
        try {
            Files.createDirectories(dir);
            Path target = dir.resolve(name);
            Files.write(target, image);
            return root.relativize(target).toString().replace('\\', '/');
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo guardar la imagen del ticket", e);
        }
    }

    public byte[] read(String relativePath) {
        try {
            return Files.readAllBytes(resolve(relativePath));
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer la imagen " + relativePath, e);
        }
    }

    public Path resolve(String relativePath) {
        Path resolved = root.resolve(relativePath).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Ruta fuera del almacén: " + relativePath);
        }
        return resolved;
    }

    /**
     * Señal secundaria de duplicado. No detecta una segunda foto del mismo
     * ticket: para eso está la clave (súper, número de recibo).
     */
    public String sha256(byte[] image) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    void replace(String relativePath, byte[] image) {
        try {
            Files.copy(new java.io.ByteArrayInputStream(image), resolve(relativePath),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo reemplazar " + relativePath, e);
        }
    }

    private String extensionOf(String fileName) {
        if (fileName == null) {
            return ".jpg";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return ".jpg";
        }
        String ext = fileName.substring(dot).toLowerCase();
        return ext.matches("\\.(jpg|jpeg|png|webp|heic)") ? ext : ".jpg";
    }
}
