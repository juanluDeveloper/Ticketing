package com.juanluidos.ticketing.validation;

import com.juanluidos.ticketing.domain.Dimension;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * Conversión a la unidad canónica de la dimensión: kg, L, ud.
 *
 * <p>Toda la aplicación normaliza ahí — serie de precios, ranking y prima de
 * preferencia — para que no haya conversiones implícitas en el comparador.
 */
public final class UnitConverter {

    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1000);

    private UnitConverter() {
    }

    /** @return el valor en la unidad canónica, o vacío si la unidad no se reconoce */
    public static Optional<BigDecimal> toCanonical(BigDecimal value, String unit) {
        if (value == null || unit == null) {
            return Optional.empty();
        }
        return switch (unit.trim().toLowerCase()) {
            case "kg" -> Optional.of(value);
            case "g", "gr" -> Optional.of(value.divide(THOUSAND, 8, RoundingMode.HALF_UP));
            case "l" -> Optional.of(value);
            case "ml" -> Optional.of(value.divide(THOUSAND, 8, RoundingMode.HALF_UP));
            case "cl" -> Optional.of(value.divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP));
            case "ud", "uds", "u", "d", "pieza" -> Optional.of(value);
            default -> Optional.empty();
        };
    }

    public static Optional<Dimension> dimensionOf(String unit) {
        if (unit == null) {
            return Optional.empty();
        }
        return switch (unit.trim().toLowerCase()) {
            case "kg", "g", "gr" -> Optional.of(Dimension.WEIGHT);
            case "l", "ml", "cl" -> Optional.of(Dimension.VOLUME);
            case "ud", "uds", "u", "d", "pieza" -> Optional.of(Dimension.UNIT);
            default -> Optional.empty();
        };
    }
}
