package com.juanluidos.ticketing.extraction;

import com.juanluidos.ticketing.config.TicketingProperties;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La reducción existe porque una foto de móvil sin tocar agota la VRAM de la
 * GTX 1070 y la extracción muere con "CUDA error: out of memory".
 */
class ImagePreprocessorTest {

    private final ImagePreprocessor preprocessor = new ImagePreprocessor(
            new TicketingProperties("x", null,
                    new TicketingProperties.Ollama("http://localhost:11434", "m", 8192, 600, 1600),
                    new TicketingProperties.Validation(new BigDecimal("0.01"), new BigDecimal("0.02")),
                    null));

    /** Un ticket fotografiado con el móvil: alto, estrecho y enorme. */
    @Test
    void scalesDownAPhonePhotoKeepingItsProportions() throws Exception {
        byte[] original = jpeg(3000, 4000);

        BufferedImage result = decode(preprocessor.prepare(original));

        assertThat(Math.max(result.getWidth(), result.getHeight())).isEqualTo(1600);
        // La proporción se mantiene: deformar un ticket estropearía el OCR.
        assertThat(result.getWidth()).isEqualTo(1200);
        assertThat(result.getHeight()).isEqualTo(1600);
    }

    @Test
    void reducesTheNumberOfBytesSubstantially() throws Exception {
        byte[] original = jpeg(3000, 4000);

        assertThat(preprocessor.prepare(original).length).isLessThan(original.length);
    }

    /** Si ya cabe, se manda tal cual: recodificar solo perdería calidad. */
    @Test
    void leavesASmallImageUntouched() {
        byte[] original = jpeg(800, 1200);

        assertThat(preprocessor.prepare(original)).isSameAs(original);
    }

    @Test
    void leavesAnImageExactlyAtTheLimitUntouched() {
        byte[] original = jpeg(1200, 1600);

        assertThat(preprocessor.prepare(original)).isSameAs(original);
    }

    /**
     * HEIC de iPhone, por ejemplo: ImageIO no lo entiende. Mejor mandarlo tal
     * cual e intentarlo que rechazar el ticket sin más.
     */
    @Test
    void passesThroughSomethingItCannotDecode() {
        byte[] notAnImage = "esto no es una imagen".getBytes();

        assertThat(preprocessor.prepare(notAnImage)).isSameAs(notAnImage);
    }

    // ------------------------------------------------------------------

    private byte[] jpeg(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        // Algo de contenido para que el JPEG no se comprima a casi nada.
        g.setColor(Color.BLACK);
        for (int y = 0; y < height; y += 20) {
            g.drawLine(0, y, width, y);
        }
        g.dispose();

        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "jpeg", out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private BufferedImage decode(byte[] bytes) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(bytes));
    }
}
