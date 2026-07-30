package com.juanluidos.ticketing.matching;

import com.juanluidos.ticketing.domain.Dimension;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Saca el tamaño del envase de la propia descripción.
 *
 * <p>Los tickets no traen el tamaño como campo aparte, pero sí embebido en el
 * texto más veces de lo que parece: "330ML", "1,10L", "180GR", "80GR", "100D".
 * Prerrellenarlo en la pantalla de validación es lo que evita teclear a mano el
 * dato del que depende todo el precio normalizado.
 *
 * <p>Es una sugerencia, no una verdad: la persona confirma o corrige.
 */
@Component
public class PackageSizeParser {

    private static final Pattern SIZE = Pattern.compile(
            "(?<![\\d.,])(\\d{1,4}(?:[.,]\\d{1,3})?)\\s*(KG|GR|G|ML|CL|L|UD|UDS|D)(?![A-Z])");

    /** @param value tamaño ya convertido a la unidad indicada */
    public record Size(BigDecimal value, String unit, Dimension dimension) {
    }

    public Optional<Size> parse(String description) {
        if (description == null) {
            return Optional.empty();
        }
        Matcher m = SIZE.matcher(TextNormalizer.normalize(description));

        Size best = null;
        while (m.find()) {
            Size candidate = toSize(m.group(1).replace(',', '.'), m.group(2));
            // Cuando hay varios candidatos gana el último: en "IFA SABE LEJIA
            // C/DET 1,10L" el tamaño va al final, y los números del principio
            // suelen ser parte del nombre comercial.
            if (candidate != null) {
                best = candidate;
            }
        }
        return Optional.ofNullable(best);
    }

    private Size toSize(String number, String unit) {
        BigDecimal value = new BigDecimal(number);
        return switch (unit) {
            case "KG" -> new Size(value, "kg", Dimension.WEIGHT);
            case "G", "GR" -> new Size(value, "g", Dimension.WEIGHT);
            case "L" -> new Size(value, "L", Dimension.VOLUME);
            case "ML" -> new Size(value, "ml", Dimension.VOLUME);
            case "CL" -> new Size(value.multiply(BigDecimal.TEN), "ml", Dimension.VOLUME);
            // "100D" son 100 dosis de detergente: dimensión unidad, no peso ni
            // volumen, y por eso no puede compartir grupo con el de litros.
            case "D", "UD", "UDS" -> new Size(value, "ud", Dimension.UNIT);
            default -> null;
        };
    }
}
