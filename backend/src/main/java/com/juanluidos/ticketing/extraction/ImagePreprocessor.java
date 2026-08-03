package com.juanluidos.ticketing.extraction;

import com.juanluidos.ticketing.config.TicketingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

/**
 * Reduce la foto antes de mandarla al modelo.
 *
 * <p>Existe por una razón medida, no por optimizar: una foto de móvil son 3-8 MB
 * con 10-50 veces más píxeles que las fotos de muestra del repositorio, el
 * codificador de visión reserva memoria en proporción, y en una GTX 1070 que ya
 * va al 96 % de VRAM con el modelo cargado eso da <em>CUDA error: out of
 * memory</em>. El smoke test pasaba porque usaba las fotos pequeñas.
 *
 * <p>El equilibrio importa en los dos sentidos: un ticket necesita resolución
 * para leerse, así que reducir de más degrada el OCR justo en el ticket denso.
 * El límite es configurable y cualquier cambio obliga a volver a mirar la
 * calidad de extracción, no solo que deje de fallar.
 *
 * <p>La imagen ORIGINAL se guarda intacta en disco: esto solo afecta a la copia
 * que viaja al modelo, así que reextraer con otro límite siempre es posible.
 */
@Component
public class ImagePreprocessor {

    private static final Logger log = LoggerFactory.getLogger(ImagePreprocessor.class);
    private static final float JPEG_QUALITY = 0.85f;

    private final int maxDimension;

    public ImagePreprocessor(TicketingProperties properties) {
        this.maxDimension = properties.ollama().maxImageDimension();
    }

    public byte[] prepare(byte[] original) {
        BufferedImage image = read(original);
        if (image == null) {
            // Formato que ImageIO no entiende, típicamente HEIC de iPhone. Se
            // manda tal cual: mejor intentarlo que rechazar el ticket.
            log.warn("No se pudo decodificar la imagen ({} KB); se envía sin reducir",
                    original.length / 1024);
            return original;
        }

        int longest = Math.max(image.getWidth(), image.getHeight());
        if (longest <= maxDimension) {
            log.info("Imagen {}x{}, {} KB: no hace falta reducirla",
                    image.getWidth(), image.getHeight(), original.length / 1024);
            return original;
        }

        double factor = (double) maxDimension / longest;
        int width = Math.max(1, (int) Math.round(image.getWidth() * factor));
        int height = Math.max(1, (int) Math.round(image.getHeight() * factor));

        byte[] scaled = encode(scale(image, width, height));
        if (scaled == null) {
            return original;
        }

        log.info("Imagen reducida de {}x{} ({} KB) a {}x{} ({} KB) para el modelo",
                image.getWidth(), image.getHeight(), original.length / 1024,
                width, height, scaled.length / 1024);
        return scaled;
    }

    private BufferedImage read(byte[] bytes) {
        try {
            return ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException e) {
            return null;
        }
    }

    private BufferedImage scale(BufferedImage source, int width, int height) {
        // TYPE_INT_RGB y no ARGB: el JPEG no lleva canal alfa y con ARGB la
        // codificación sale con los colores cambiados.
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = target.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(source, 0, 0, width, height, null);
        g.dispose();
        return target;
    }

    private byte[] encode(BufferedImage image) {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            return null;
        }
        ImageWriter writer = writers.next();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            ImageWriteParam params = writer.getDefaultWriteParam();
            if (params.canWriteCompressed()) {
                params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                params.setCompressionQuality(JPEG_QUALITY);
            }
            writer.write(null, new IIOImage(image, null, null), params);
            return out.toByteArray();
        } catch (IOException e) {
            log.warn("No se pudo recodificar la imagen reducida", e);
            return null;
        } finally {
            writer.dispose();
        }
    }
}
