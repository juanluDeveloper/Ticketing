package com.juanluidos.ticketing.domain;

/**
 * Dimensión física de comparación. Unidades base: g, ml, ud.
 *
 * <p>Un {@link ComparableGroup} no puede mezclar dimensiones: detergente en
 * dosis ("DET MARSE FLOTA 100D") y detergente en litros ("FLOTA LAVAV. 1,10L")
 * no son comparables entre sí.
 */
public enum Dimension {
    WEIGHT("g"),
    VOLUME("ml"),
    UNIT("ud");

    private final String baseUnit;

    Dimension(String baseUnit) {
        this.baseUnit = baseUnit;
    }

    public String getBaseUnit() {
        return baseUnit;
    }
}
