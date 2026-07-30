package com.juanluidos.ticketing.matching;

import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * Normalización de descripciones para el matcher.
 *
 * <p>La primera pasada del matcher busca coincidencia exacta sobre el texto
 * normalizado, que resuelve la mayoría de las líneas porque los tickets repiten
 * la misma cadena compra tras compra. Los trigramas solo entran para el resto.
 */
public final class TextNormalizer {

    private static final Pattern DIACRITICS = Pattern.compile("\\p{InCombiningDiacriticalMarks}+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    /** Bloques CJK: ideogramas unificados y sus extensiones más comunes. */
    private static final Pattern CJK = Pattern.compile("[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}]");

    private TextNormalizer() {
    }

    /** Mayúsculas, sin acentos, espacios colapsados. */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String decomposed = Normalizer.normalize(raw, Normalizer.Form.NFD);
        String withoutAccents = DIACRITICS.matcher(decomposed).replaceAll("");
        return WHITESPACE.matcher(withoutAccents.trim()).replaceAll(" ").toUpperCase();
    }

    /**
     * Cola en alfabeto latino, para las descripciones de Xinya. pg_trgm rinde mal
     * sobre chino, así que la similitud se calcula sobre esta parte y el chino se
     * reserva para la coincidencia exacta.
     *
     * @return null si el texto no lleva CJK, porque entonces no hace falta
     */
    public static String latinTail(String raw) {
        if (raw == null || !CJK.matcher(raw).find()) {
            return null;
        }
        String stripped = CJK.matcher(raw).replaceAll(" ");
        String normalized = normalize(stripped);
        return normalized == null || normalized.isBlank() ? null : normalized;
    }
}
